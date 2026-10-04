# Test support

`EmptyHistoryFixture` deliberately supplies no-op message storage for focused tests that
replace selected repository operations. Its task map belongs to each fixture instance.
Only test source sets depend on this module. Production bundles use real native Room or
an owned in-memory repository; they never import this fixture.
