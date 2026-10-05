# Private model adapter support

This build library owns registration mechanics shared by independent model providers.
It contains no vendor factories, catalogs, dependencies, defaults, labels or UI. It is
bundled privately into each provider archive, has no service/plugin release of its own,
and is not part of the shared host ABI. Public service contracts belong to plugins/api.
