# Test support

`EmptyHistoryFixture` deliberately supplies no-op message storage for focused tests that
replace selected repository operations. Its task map belongs to each fixture instance.
Only test source sets depend on this module. Production bundles use real native Room or
an owned in-memory repository; they never import this fixture.

`LegacySettings` and its test extensions construct/read historical JSON migration bags.
Absent arguments stay absent. They are used only by tests exercising old configuration;
current feature values should be asserted through their owned namespace/policy. These helpers
are not SDK fields, production providers, host exports or a current schema contract.
