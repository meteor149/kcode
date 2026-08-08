// Based on the Apache-2.0 AndroidX Room web demo worker protocol.
import sqlite3InitModule from '@sqlite.org/sqlite-wasm';

let sqlite3 = null;
const databases = new Map();
const statements = new Map();
let nextDatabaseId = 1;
let nextStatementId = 1;

function respond(id, data) {
    postMessage({ id, data });
}

function fail(id, error) {
    postMessage({ id, error: error instanceof Error ? error.message : String(error) });
}

function openRequest(id, request) {
    try {
        const databaseId = nextDatabaseId++;
        databases.set(databaseId, new sqlite3.oo1.OpfsDb(request.fileName));
        respond(id, { databaseId });
    } catch (error) {
        fail(id, error);
    }
}

function prepareRequest(id, request) {
    try {
        const database = databases.get(request.databaseId);
        if (!database) throw new Error(`Invalid database ID: ${request.databaseId}`);
        const statement = database.prepare(request.sql);
        const statementId = nextStatementId++;
        statements.set(statementId, statement);
        const columnNames = [];
        for (let i = 0; i < statement.columnCount; i++) {
            columnNames.push(sqlite3.capi.sqlite3_column_name(statement, i));
        }
        respond(id, {
            statementId,
            parameterCount: sqlite3.capi.sqlite3_bind_parameter_count(statement),
            columnNames,
        });
    } catch (error) {
        fail(id, error);
    }
}

function stepRequest(id, request) {
    try {
        const statement = statements.get(request.statementId);
        if (!statement) throw new Error(`Invalid statement ID: ${request.statementId}`);
        statement.reset();
        statement.clearBindings();
        request.bindings.forEach((value, index) => statement.bind(index + 1, value));
        const rows = [];
        const columnTypes = [];
        while (statement.step()) {
            if (columnTypes.length === 0) {
                for (let i = 0; i < statement.columnCount; i++) {
                    columnTypes.push(sqlite3.capi.sqlite3_column_type(statement, i));
                }
            }
            rows.push(statement.get([]));
        }
        respond(id, { rows, columnTypes });
    } catch (error) {
        fail(id, error);
    }
}

function closeRequest(id, request) {
    try {
        if (request.statementId != null) {
            const statement = statements.get(request.statementId);
            if (!statement) throw new Error(`Invalid statement ID: ${request.statementId}`);
            statement.finalize();
            statements.delete(request.statementId);
        }
        if (request.databaseId != null) {
            const database = databases.get(request.databaseId);
            if (!database) throw new Error(`Invalid database ID: ${request.databaseId}`);
            database.close();
            databases.delete(request.databaseId);
        }
        respond(id, {});
    } catch (error) {
        fail(id, error);
    }
}

const handlers = {
    open: openRequest,
    prepare: prepareRequest,
    step: stepRequest,
    close: closeRequest,
};

function handleMessage(event) {
    const request = event.data;
    const command = request?.data?.cmd;
    const handler = handlers[command];
    if (!handler) {
        fail(request?.id, `Invalid SQLite worker command: ${command}`);
        return;
    }
    handler(request.id, request.data);
}

const queue = [];
onmessage = event => sqlite3 ? handleMessage(event) : queue.push(event);

sqlite3InitModule().then(instance => {
    sqlite3 = instance;
    while (queue.length > 0) handleMessage(queue.shift());
});
