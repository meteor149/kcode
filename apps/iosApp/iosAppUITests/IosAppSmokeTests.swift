import XCTest
import UIKit

final class IosAppSmokeTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = false
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15))
    }

    func testNavigationSmoke() {
        app.buttons["打开侧栏"].tap()
        XCTAssertTrue(app.buttons["打开设置"].waitForExistence(timeout: 3))
        app.buttons["打开设置"].tap()
        XCTAssertTrue(app.staticTexts["设置"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "模型服务")).firstMatch.exists)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "互联网搜索")).firstMatch.exists)
    }

    func testDeepSeekAndExaEndToEnd() throws {
        guard let credentialText = UIPasteboard.general.string, !credentialText.isEmpty else {
            throw XCTSkip("DeepSeek and Exa credentials were not supplied to the simulator pasteboard")
        }
        let credentials = credentialText.split(separator: "\n", omittingEmptySubsequences: false)
        XCTAssertEqual(credentials.count, 2)
        let deepSeekKey = String(credentials[0])
        let exaKey = String(credentials[1])
        app.terminate()
        app.launchEnvironment["KCODE_TEST_DEEPSEEK_KEY"] = deepSeekKey
        app.launchEnvironment["KCODE_TEST_EXA_KEY"] = exaKey
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15))

        sendMessage("把 KCODE、DEEPSEEK、OK 三个单词直接连接，不要空格或标点，只输出结果。")
        XCTAssertTrue(waitForReply(containing: "KCODEDEEPSEEKOK", timeout: 90), "DeepSeek did not return the expected response")

        sendMessage("必须调用互联网搜索工具查询 Exa 官方网站。回答时先把 KCODE、EXA、OK 三个单词直接连接，再写找到的网站域名。")
        XCTAssertTrue(waitForReply(containing: "KCODEEXAOK", timeout: 120), "The Exa-backed search flow did not return the expected response")

        app.terminate()
        app.launchEnvironment.removeAll()
        app.launch()
        XCTAssertTrue(
            app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "DeepSeek V4 Flash"))
                .firstMatch.waitForExistence(timeout: 15)
        )
    }

    private func openSettings() {
        app.buttons["打开侧栏"].tap()
        XCTAssertTrue(app.buttons["打开设置"].waitForExistence(timeout: 3))
        app.buttons["打开设置"].tap()
    }

    private func sendMessage(_ text: String) {
        let composer = app.textViews.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 3))
        composer.tap()
        composer.typeText(text)
        app.keyboards.buttons["Return"].tapIfExists()
        let send = app.buttons["发送消息"]
        XCTAssertTrue(send.waitForExistence(timeout: 3))
        send.tap()
    }

    private func waitForReply(containing marker: String, timeout: TimeInterval) -> Bool {
        let reply = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch
        let completed = reply.waitForExistence(timeout: timeout)
        if !completed { print("REPLY_FAILURE_TREE_BEGIN\n\(app.debugDescription)\nREPLY_FAILURE_TREE_END") }
        return completed
    }
}

private extension XCUIElement {
    func tapIfExists() {
        if exists { tap() }
    }
}
