# Skill tool consumer

`consumer.tools.skill` consumes `KcodeTools` and `KcodeSkills`, registering skill tools
when a skill runtime is supplied. It borrows the runtime and removes its contribution
on withdrawal. Missing services suspend the consumer until replacements return.

Native builds distribute a dual-target `.kplugin` with
`ai.meteor.kcode.plugin.feature.SkillToolConsumerPlugin` and `Unit` configuration.
The consumer implementation stays outside the host runtime dependencies. Platform hosts
offer this package with or without the default product composition.

Production-classpath JAR and real APK tests check host class absence, contribution
withdrawal and exactly one contribution on recovery. The desktop fixture also removes
the filesystem, causing the skills provider and this consumer to become Pending.
