# Neutral UI contribution registry

`core.ui-contributions` / `UiContributionsServicePlugin` owns `KcodeUiContributions` and
its revocable projections. It depends only on the neutral plugin SDK and contains no default
page, navigation, settings, theme, or presentation implementation. Alternative roots can use
this package without the default UI contracts or pages. Withdrawal cancels and joins active
projection preparation before closing the registry.
