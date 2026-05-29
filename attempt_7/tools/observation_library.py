"""Observation library — Stack-Overflow-style (pattern, diagnosis, fix) entries.

Each entry says: when an observation matches `pattern`, the diagnosis is `diagnosis`,
and the fix is `fix_snippet` (a step or recipes list) at `fix_placement` (where to put
it in the next chain). The harness scans the chain's observation after each failed
attempt and surfaces matching entries to the proposer.

This replaces the cluster approach: instead of predicting the chain from project
features, we react to actual observed failures with verified fixes.
"""
import re

LIBRARY = [

    # ───── WebSecurityConfigurerAdapter removed in Spring Security 6 ───────
    {
        "id": "wsca_removed_in_ss6",
        "pattern": re.compile(
            r"cannot find symbol[^\n]*\n[^\n]*symbol:\s+class WebSecurityConfigurerAdapter|"
            r"package org\.springframework\.security\.config\.annotation\.web\.configuration[^\n]*WebSecurityConfigurerAdapter|"
            r"WebSecurityConfigurerAdapter.*does not exist",
        ),
        "diagnosis": (
            "Spring Security 6 (pulled in by Spring Boot 3) removed "
            "`WebSecurityConfigurerAdapter`. Any class still doing "
            "`extends WebSecurityConfigurerAdapter` fails to compile post-SB3. "
            "The upstream `org.openrewrite.java.spring.security5.WebSecurityConfigurerAdapter` "
            "recipe is marker-only — it inserts a TODO comment and leaves the "
            "class extending WSCA. Use the claude-recipe that actually performs "
            "the AST rewrite: drops `extends WSCA`, converts "
            "`configure(HttpSecurity)` into a `@Bean SecurityFilterChain` method "
            "(adds `return http.build();`), and deletes super-delegating "
            "`authenticationManagerBean()` overrides."
        ),
        "fix_snippet": {
            "label": "wsca_to_filter_chain",
            "jdk": 17,
            "recipes": ["com.claude.recipes.RewriteWebSecurityConfigurerAdapterToFilterChain"],
        },
        "fix_placement": "insert_after:sb3",
        "notes": (
            "Custom claude-recipe (AST-aware). Gated on UsesType<WSCA>; no-ops "
            "on projects without WSCA. Run at jdk=17. If your project also "
            "exposed AuthenticationManager via @Bean(BeanIds.AUTHENTICATION_MANAGER) "
            "and other beans inject it, you may need to re-add an "
            "AuthenticationConfiguration-based @Bean manually — this recipe "
            "removes that exposure."
        ),
    },

# ───── SpringFox removed in Spring Boot 3 ───────────────────────────────
    {
        "id": "springfox_unavailable_in_sb3",
        "pattern": re.compile(
            r"package\s+springfox\.documentation(\.\w+)*\s+does not exist|"
            r"package\s+io\.swagger\.annotations\s+does not exist",
        ),
        "diagnosis": (
            "SpringFox is abandoned and incompatible with Spring Boot 3.x. "
            "The project's `springfox-*` deps and `springfox.documentation.*` "
            "imports don't resolve under SB 3. Migrate to springdoc-openapi "
            "(the modern Swagger/OpenAPI integration for SB 3) using "
            "OpenRewrite's `SpringFoxToSpringDoc` meta-recipe — it removes "
            "springfox deps, adds springdoc-openapi-ui, and rewrites all "
            "`@EnableSwagger2`/`Docket`/etc. usages to the springdoc equivalents."
        ),
        "fix_snippet": {
            "label": "springfox_to_springdoc",
            "jdk": 17,
            "recipes": ["org.openrewrite.java.springdoc.SpringFoxToSpringDoc"],
        },
        "fix_placement": "insert_before:upgrade_spring_boot_3_3",
        "notes": "Run BEFORE the SB-3 upgrade — replaces springfox deps so SB-3 doesn't try to keep them. If no SB-3 step labels match, place this step right before whichever step's failure observation surfaces 'package springfox does not exist'.",
    },

    # ───── SB-2's bundled ASM can't read modern bytecode ────────────────────
    {
        "id": "sb2_asm_unsupported_class_version",
        "pattern": re.compile(
            r"Unsupported class file major version (5[3-9]|6[0-9])|"
            r"ASM ClassReader failed to parse class file",
        ),
        "diagnosis": (
            "Spring Boot 2.x bundles an ASM version that can't parse Java 17+ "
            "bytecode (class file major versions 61=Java 17, 65=Java 21). The "
            "chain compiled the project to a newer bytecode version, but "
            "Spring's internal ClassReader inside the test context chokes. "
            "Upgrade Spring Boot to 3.x BEFORE bumping the JDK target so SB-3's "
            "bundled ASM can handle the new bytecode."
        ),
        "fix_snippet": {
            "label": "sb3",
            "jdk": 17,
            "recipes": ["org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3"],
        },
        "fix_placement": "replace_step:upgrade_build_to_java17",
        "notes": "Same fix as sb2_javax_on_jdk17plus — different observation, same root cause: SB-2 can't survive a Java 17+ world.",
    },

    # ───── SB-2 javax on JDK 17+ ────────────────────────────────────────────
    {
        "id": "sb2_javax_on_jdk17plus",
        "pattern": re.compile(
            r"package\s+javax\.(persistence|servlet|validation|xml\.bind|annotation)\s+does not exist",
            re.M,
        ),
        "diagnosis": (
            "Project source still references javax.* packages that are unbundled "
            "from JDK 11+ AND no jakarta migration has run yet. The project is on "
            "Spring Boot 2.x; the default `upgrade_build_to_java17` compiles SB-2 "
            "code under JDK 17 where javax.* is gone. The fix is to migrate the "
            "javax.* packages to jakarta.* using the SB-3 meta-recipe, which "
            "internally composes the javax→jakarta migration + the SB version bump + "
            "the JDK target bump."
        ),
        "fix_snippet": {
            "label": "sb3",
            "jdk": 17,
            "recipes": ["org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3"],
        },
        "fix_placement": "replace_step:upgrade_build_to_java17",
        "notes": "Do NOT add a separate jakarta_migration step — UpgradeSpringBoot_3_3 already invokes JavaxMigrationToJakarta internally.",
    },

    # ───── Hallucinated jakarta fanout recipe names ─────────────────────────
    {
        "id": "hallucinated_jakarta_fanout",
        "pattern": re.compile(
            r"recipe\s+'org\.openrewrite\.java\.migrate\.jakarta\.Javax\w*MigrationToJakarta\w*'\s+does not exist",
        ),
        "diagnosis": (
            "The OpenRewrite jakarta namespace does NOT contain per-area fanout "
            "recipes like JavaxServletMigrationToJakartaServlet. The only real "
            "recipe is the meta `JavaxMigrationToJakarta`, and it is already "
            "invoked internally by `UpgradeSpringBoot_3_3`. A separate jakarta_migration "
            "step is almost always redundant."
        ),
        "fix_snippet": None,  # removal, not addition
        "fix_placement": "remove_step:jakarta_migration",
        "notes": "Remove any jakarta_migration step and rely on UpgradeSpringBoot_3_3 to handle javax→jakarta.",
    },

    # ───── H2 1.x → 2.x SQL syntax break ────────────────────────────────────
    {
        "id": "h2_legacy_identity_syntax",
        "pattern": re.compile(
            r"Syntax error in SQL statement.*IDENTITY",
            re.S,
        ),
        "diagnosis": (
            "H2 2.x rejects legacy `CREATE TABLE … IDENTITY` syntax used by "
            "Flyway/Liquibase migrations on older JHipster scaffolds. H2 was "
            "transitively bumped to 2.x by Spring Boot 3.x."
        ),
        "fix_snippet": {
            "label": "pin_h2",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.maven.UpgradeDependencyVersion",
                 "groupId": "com.h2database", "artifactId": "h2", "newVersion": "1.4.200"},
                {"name": "org.openrewrite.maven.ChangePropertyValue",
                 "key": "h2.version", "newValue": "1.4.200", "addIfMissing": "true"},
            ],
        },
        "fix_placement": "insert_after:sb3",
        "notes": "Pin h2 BEFORE the next step rebuilds; addIfMissing safely no-ops on projects without h2.",
    },

    # ───── Spring 6 PathPatternParser regex rejection ───────────────────────
    {
        "id": "spring6_pathpattern_regex_rejection",
        "pattern": re.compile(
            r"Invalid mapping on handler class.*FrontendController|PathPattern.*could not be parsed|"
            r"requestMappingHandlerMapping.*Invalid mapping",
        ),
        "diagnosis": (
            "Spring 6's default PathPatternParser rejects regex/glob patterns like "
            "`@GetMapping(\"/**/{path:[^.]*}\")` commonly used in JHipster-style "
            "FrontendControllers. Opt back into the legacy AntPathMatcher via "
            "application.properties."
        ),
        "fix_snippet": {
            "label": "set_pathmatch",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.properties.AddProperty",
                 "property": "spring.mvc.pathmatch.matching-strategy",
                 "value": "ant_path_matcher"},
            ],
        },
        "fix_placement": "insert_after:sb3",
    },

    # ───── Spring Security 6 oauth2Login entry-point precedence ─────────────
    {
        "id": "ss6_oauth2_login_supersedes_global_ep",
        "pattern": re.compile(
            r"Response status expected:<(401|403)> but was:<302>|Status expected:<(401|403)> but was:<302>",
        ),
        "diagnosis": (
            "Spring Security 6 oauth2Login DSL registers a redirect-style entry "
            "point that supersedes the global `Http403ForbiddenEntryPoint`. Test "
            "requests against `@WebMvcTest` slices get 302 (OAuth redirect) "
            "instead of expected 401/403. Use the matcher-keyed entry point so "
            "API requests still get 401/403 while browser flows get the redirect."
        ),
        "fix_snippet": {
            "label": "scope_security_entrypoint",
            "jdk": 17,
            "recipes": [
                {"name": "com.claude.recipes.ScopeAuthenticationEntryPointToApiForOAuth2Login",
                 "apiPathPattern": "/api/**"},
            ],
        },
        "fix_placement": "insert_after:sb3",
        "notes": "Recipe is gated on UsesMethod for HttpSecurity.oauth2Login(..); no-ops on projects without oauth2Login.",
    },


    # ───── Mockito 5 final-method mocking behavior change ───────────────────
    {
        "id": "mockito5_mocks_final_by_default",
        "pattern": re.compile(
            r"PreAuthorize.*currentUser|test.*expected.*member.*false|Status expected:<200> but was:<403>",
        ),
        "diagnosis": (
            "Mockito 5 (transitive via Spring Boot 3) mocks `final` methods by "
            "default whereas Mockito 3/4 didn't. Test base classes that stub "
            "`@MockBean` services via `when(service.get()).thenReturn(...)` and "
            "rely on `final` accessor methods (like `isMember()`) falling through "
            "to the real implementation are broken. Pin Mockito back to 4.x."
        ),
        "fix_snippet": {
            "label": "pin_mockito",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.maven.ChangePropertyValue",
                 "key": "mockito.version", "newValue": "4.11.0", "addIfMissing": "true"},
            ],
        },
        "fix_placement": "insert_after:sb3",
        "notes": "Pattern is loose (403-when-200 expected can have many causes); cross-check with code_signals.uses_mockbean if matching is ambiguous.",
    },

    # ───── Hibernate JPA modelgen NPE inside javac ──────────────────────────
    {
        "id": "jpamodelgen_npe_inside_javac",
        "pattern": re.compile(
            r"NullPointerException.*JavacProcessingEnvironment\.callProcessor|"
            r"discoverAndRunProcs|Round\.run",
            re.S,
        ),
        "diagnosis": (
            "An annotation processor in `<annotationProcessorPaths>` is too old "
            "for the target JDK and NPEs inside javac during processing rounds. "
            "Most common: `hibernate-jpamodelgen` pinned to the Hibernate-5.2 "
            "line in old JHipster parents. Force the AP version to a JDK-17-safe "
            "release."
        ),
        "fix_snippet": {
            "label": "force_jpamodelgen",
            "jdk": 11,
            "recipes": [
                {"op": "force_version", "artifactId": "hibernate-jpamodelgen", "version": "5.6.15.Final"},
            ],
        },
        "fix_placement": "insert_before:first_failing_step",
        "notes": "This is a pom_patch step (op-based), not an OpenRewrite recipe. Use the harness's pom_patch primitive.",
    },

    # ───── JAXB unbundled from JDK 11+ ──────────────────────────────────────
    {
        "id": "javax_xml_bind_noclassdef",
        "pattern": re.compile(
            r"NoClassDefFoundError.*javax/xml/bind/JAXBException|"
            r"NoClassDefFoundError.*javax\.xml\.bind",
        ),
        "diagnosis": (
            "JAXB was unbundled from the JDK in version 11. Project still has "
            "javax.xml.bind imports (or an annotation processor that uses JAXB) "
            "but no JAXB dependency. Restore JAXB at BOTH the compile classpath "
            "AND the annotation-processor classpath — they're separate."
        ),
        "fix_snippet": {
            "label": "bundle_jaxb",
            "jdk": 11,
            "recipes": [
                {"op": "add_dependency",
                 "groupId": "javax.xml.bind", "artifactId": "jaxb-api", "version": "2.3.1"},
                {"op": "add_dependency",
                 "groupId": "org.glassfish.jaxb", "artifactId": "jaxb-runtime", "version": "2.3.1"},
                {"op": "add_ap_path",
                 "groupId": "javax.xml.bind", "artifactId": "jaxb-api", "version": "2.3.1"},
                {"op": "add_ap_path",
                 "groupId": "org.glassfish.jaxb", "artifactId": "jaxb-runtime", "version": "2.3.1"},
            ],
        },
        "fix_placement": "insert_before:first_failing_step",
        "notes": "pom_patch step (op-based). A lone add_dependency is usually insufficient — the AP classpath is separate.",
    },
    # ───── thymeleaf-extras-springsecurity5 gone in SB3 ────────────────────
    {
        "id": "thymeleaf_extras_springsecurity5_in_sb3",
        "pattern": re.compile(
            r"package org\.thymeleaf\.extras\.springsecurity5[^\s]* does not exist|"
            r"cannot find symbol[^\n]*\n[^\n]*symbol:\s+class SpringSecurityDialect",
        ),
        "diagnosis": (
            "Thymeleaf's Spring Security integration is versioned to match SS "
            "major. SS6 (pulled in by SB3) requires "
            "`thymeleaf-extras-springsecurity6` instead of `…security5`. "
            "Bump the dependency and rewrite imports."
        ),
        "fix_snippet": {
            "label": "thymeleaf_extras_ss5_to_ss6",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.maven.ChangeDependencyGroupIdAndArtifactId",
                 "oldGroupId": "org.thymeleaf.extras",
                 "oldArtifactId": "thymeleaf-extras-springsecurity5",
                 "newArtifactId": "thymeleaf-extras-springsecurity6"},
                {"name": "org.openrewrite.java.ChangePackage",
                 "oldPackageName": "org.thymeleaf.extras.springsecurity5",
                 "newPackageName": "org.thymeleaf.extras.springsecurity6",
                 "recursive": True},
            ],
        },
        "fix_placement": "fold_into:sb3",
        "notes": "Pair the dependency artifactId rename with a recursive package rename.",
    },

    # ───── SB3 moved Security/Management auto-config classes ────────────────
    {
        "id": "sb3_security_autoconfig_moved",
        "pattern": re.compile(
            r"symbol:\s+class SecurityAutoConfiguration|symbol:\s+class ManagementWebSecurityAutoConfiguration",
        ),
        "diagnosis": (
            "Spring Boot 3 split the autoconfigure namespace by servlet/reactive: "
            "`SecurityAutoConfiguration` moved from "
            "`o.s.b.autoconfigure.security` to "
            "`o.s.b.autoconfigure.security.servlet`; "
            "`ManagementWebSecurityAutoConfiguration` moved from "
            "`o.s.b.actuate.autoconfigure` to "
            "`o.s.b.actuate.autoconfigure.security.servlet`."
        ),
        "fix_snippet": {
            "label": "sb3_autoconfig_imports",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.java.ChangeType",
                 "oldFullyQualifiedTypeName": "org.springframework.boot.autoconfigure.security.SecurityAutoConfiguration",
                 "newFullyQualifiedTypeName": "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration"},
                {"name": "org.openrewrite.java.ChangeType",
                 "oldFullyQualifiedTypeName": "org.springframework.boot.actuate.autoconfigure.ManagementWebSecurityAutoConfiguration",
                 "newFullyQualifiedTypeName": "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"},
            ],
        },
        "fix_placement": "fold_into:sb3",
        "notes": "Both classes survive in SB3 under the new packages.",
    },
    # ───── @WebMvcTest(secure = false) removed in SB3 ─────────────────────
    {
        "id": "webmvctest_secure_attribute_removed_sb3",
        "pattern": re.compile(
            r"cannot find symbol[^\n]*\n[^\n]*symbol:\s+method secure\(\)[^\n]*\n[^\n]*location:\s+@interface\s+org\.springframework\.boot\.test\.autoconfigure\.web\.servlet\.WebMvcTest",
        ),
        "diagnosis": (
            "Spring Boot 3 removed the `secure` attribute on `@WebMvcTest` "
            "(it was deprecated in SB 2.x). Tests written as "
            "`@WebMvcTest(secure = false)` no longer compile. Strip the "
            "attribute via RemoveAnnotationAttribute."
        ),
        "fix_snippet": {
            "label": "strip_webmvctest_secure",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.java.RemoveAnnotationAttribute",
                 "annotationType": "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest",
                 "attributeName": "secure"},
            ],
        },
        "fix_placement": "fold_into:sb3",
        "notes": "Fold into sb3 step so the annotation rewrite runs before test-compile.",
    },
    # ───── @ConstructorBinding removed in SB3 (deprecated since 2.2) ─────────
    {
        "id": "constructor_binding_removed_in_sb3",
        "pattern": re.compile(
            r"cannot find symbol[^\n]*\n[^\n]*symbol:\s+class ConstructorBinding|"
            r"package org\.springframework\.boot\.context\.properties[^\n]*ConstructorBinding",
        ),
        "diagnosis": (
            "Spring Boot 3 removed `@ConstructorBinding` (deprecated since 2.2). "
            "SB3 constructor-binds @ConfigurationProperties classes automatically. "
            "Fold the upstream `RemoveConstructorBindingAnnotation` recipe into the "
            "sb3 step so the source is rewritten before the post-step compile gate."
        ),
        "fix_snippet": {
            "label": "strip_constructor_binding",
            "jdk": 17,
            "recipes": [
                "org.openrewrite.java.spring.boot3.RemoveConstructorBindingAnnotation",
                {"name": "org.openrewrite.java.RemoveAnnotation",
                 "annotationPattern": "@org.springframework.boot.context.properties.ConstructorBinding"},
            ],
        },
        "fix_placement": "fold_into:sb3",
        "notes": "Recipe lives in rewrite-spring; UpgradeSpringBoot_3_3 surprisingly does NOT compose it. Folding into sb3 ensures both run before build_post.",
    },
    # ───── Hibernate 5 internal CurrentTimestampFunction.NAME gone in H6 ───
    {
        "id": "hibernate5_currenttimestamp_internal",
        "pattern": re.compile(
            r"package org\.hibernate\.query\.criteria\.internal\.expression\.function does not exist|"
            r"cannot find symbol[^\n]*\n[^\n]*symbol:\s+class CurrentTimestampFunction",
        ),
        "diagnosis": (
            "Hibernate 6 removed `org.hibernate.query.criteria.internal.expression.function.CurrentTimestampFunction`. "
            "The constant `CurrentTimestampFunction.NAME` resolves to the literal string `\"current_timestamp\"`, which "
            "Hibernate 6's `@ColumnDefault` still accepts. Inline the constant via ReplaceConstantWithAnotherConstant, "
            "then sweep unused imports."
        ),
        "fix_snippet": {
            "label": "inline_hibernate_currenttimestamp",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.java.ReplaceConstant",
                 "owningType": "org.hibernate.query.criteria.internal.expression.function.CurrentTimestampFunction",
                 "constantName": "NAME",
                 "literalValue": '"current_timestamp"'},
                "org.openrewrite.java.RemoveUnusedImports",
            ],
        },
        "fix_placement": "fold_into:sb3",
        "notes": "ReplaceConstantWithAnotherConstant inlines the literal value; H6 still accepts the string. No claude-recipe needed.",
    },
    # ───── ResponseEntity.getStatusCode() returns HttpStatusCode in Spring 6 ──
    {
        "id": "response_entity_get_status_code_returns_httpstatuscode",
        "pattern": re.compile(
            r"incompatible types:\s+org\.springframework\.http\.HttpStatusCode cannot be converted to org\.springframework\.http\.HttpStatus|"
            r"cannot find symbol[^\n]*\n[^\n]*symbol:\s+method getReasonPhrase\(\)[^\n]*\n[^\n]*location:\s+variable\s+\w+\s+of type org\.springframework\.http\.HttpStatusCode",
        ),
        "diagnosis": (
            "Spring 6 widened `ResponseEntity.getStatusCode()` return type from `HttpStatus` "
            "to its `HttpStatusCode` superinterface. Variable declarations like "
            "`HttpStatus status = re.getStatusCode();` no longer compile. The claude-recipe "
            "`WidenHttpStatusToHttpStatusCode` widens the LHS type to `HttpStatusCode` and "
            "updates imports. If downstream code then needs `.getReasonPhrase()` (only on "
            "`HttpStatus`), the operator must convert via `HttpStatus.valueOf(status.value())`."
        ),
        "fix_snippet": {
            "label": "widen_httpstatus_to_httpstatuscode",
            "jdk": 17,
            "recipes": ["com.claude.recipes.WidenHttpStatusToHttpStatusCode"],
        },
        "fix_placement": "fold_into:sb3",
        "notes": "Custom claude-recipe (AST-aware). Gated on UsesType<HttpStatus> AND UsesType<ResponseEntity>; no-op on projects without both.",
    },
    # ───── kotlin-maven-plugin too old for jvmTarget=21 ──────────────────
    {
        "id": "kotlin_maven_plugin_too_old_for_jvm21",
        "pattern": re.compile(
            r"[Uu]nknown JVM target version[^\n]*|"
            r"kotlin-maven-plugin[^\n]*compile[^\n]*[Cc]ompilation failure|"
            r"Module was compiled with an incompatible version of Kotlin|"
            r"target value 21 is not supported by Kotlin",
        ),
        "diagnosis": (
            "Kotlin compiler plugin < 1.9.20 doesn't accept jvmTarget=21. "
            "Bump to 1.9.25+ via UpgradePluginVersion."
        ),
        "fix_snippet": {
            "label": "bump_kotlin_plugin",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.maven.UpgradePluginVersion",
                 "groupId": "org.jetbrains.kotlin",
                 "artifactId": "kotlin-maven-plugin",
                 "newVersion": "1.9.25"},
            ],
        },
        "fix_placement": "insert_before:upgrade_build_to_java21",
    },

    # ───── spotify-fmt-maven-plugin too old for JDK 21 ───────────────────
    {
        "id": "spotify_fmt_plugin_too_old_for_jdk21",
        "pattern": re.compile(
            r"com\.spotify\.fmt:fmt-maven-plugin[^\n]*NoSuchMethodError|"
            r"google-java-format[^\n]*java\.lang\.NoSuchMethodError",
        ),
        "diagnosis": (
            "spotify fmt-maven-plugin < 2.23 uses google-java-format ≤ 1.15, "
            "which throws NoSuchMethodError on JDK 21. Bump to 2.23."
        ),
        "fix_snippet": {
            "label": "bump_spotify_fmt",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.maven.UpgradePluginVersion",
                 "groupId": "com.spotify.fmt",
                 "artifactId": "fmt-maven-plugin",
                 "newVersion": "2.23"},
            ],
        },
        "fix_placement": "insert_before:upgrade_build_to_java21",
    },

    # ───── lombok < 1.18.30 NoSuchFieldError on JDK 21 javac AST ──────────
    {
        "id": "lombok_too_old_for_jdk21",
        "pattern": re.compile(
            r"NoSuchFieldError[^\n]*JCTree\$JCImport[^\n]*qualid|"
            r"java\.lang\.NoSuchFieldError: Class com\.sun\.tools\.javac\.tree\.JCTree[^\n]*",
        ),
        "diagnosis": (
            "Lombok < 1.18.30 cannot read JDK 21's javac AST (NoSuchFieldError on "
            "JCTree$JCImport.qualid). Bump lombok to 1.18.34+ and the "
            "maven-compiler-plugin to 3.13.0+ which understands the JDK 21 source."
        ),
        "fix_snippet": {
            "label": "bump_lombok_and_compiler_for_jdk21",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.maven.UpgradeDependencyVersion",
                 "groupId": "org.projectlombok", "artifactId": "lombok",
                 "newVersion": "1.18.34"},
                {"name": "org.openrewrite.maven.ChangePropertyValue",
                 "key": "lombok.version", "newValue": "1.18.34"},
                {"name": "org.openrewrite.maven.UpgradePluginVersion",
                 "groupId": "org.apache.maven.plugins",
                 "artifactId": "maven-compiler-plugin",
                 "newVersion": "3.13.0"},
            ],
        },
        "fix_placement": "insert_before:upgrade_build_to_java21",
    },

    # ───── maven-compiler-plugin 3.11+ rejects "1.8" source string ────────
    {
        "id": "compiler_source_version_string_format",
        "pattern": re.compile(
            r"source level should be in '1\.1'[^\n]*: 17|"
            r"For input string|"
            r"javac: invalid source release: 1\.8",
        ),
        "diagnosis": (
            "maven-compiler-plugin 3.11+ requires bare integer source/target "
            "values (`8`, not `1.8`). Older POMs use the legacy `1.8` literal "
            "which fails to parse. Use ChangePropertyValue or sed-style on "
            "<source>/<target> tags to normalize."
        ),
        "fix_snippet": {
            "label": "normalize_compiler_source_version",
            "jdk": 17,
            "recipes": [
                {"name": "org.openrewrite.maven.ChangePropertyValue",
                 "key": "maven.compiler.source", "newValue": "17"},
                {"name": "org.openrewrite.maven.ChangePropertyValue",
                 "key": "maven.compiler.target", "newValue": "17"},
                {"name": "org.openrewrite.maven.ChangePropertyValue",
                 "key": "java.version", "newValue": "17"},
            ],
        },
        "fix_placement": "insert_before:upgrade_build_to_java21",
        "notes": "ChangePropertyValue only catches property-driven versions; for literal <source>1.8</source> tags inside the pom, a separate xml rewrite is needed.",
    },


]


def match_library(observation_text):
    """Return list of LIBRARY entries whose pattern matches the observation.

    Multiple entries may match. The proposer integrates each matched fix into
    the next chain attempt.
    """
    if not observation_text:
        return []
    return [e for e in LIBRARY if e["pattern"].search(observation_text)]


def render_matches_for_prompt(matches):
    """Render a list of matched entries as a prompt-friendly text block."""
    if not matches:
        return ""
    import json as _json
    out = ["=== OBSERVATION LIBRARY MATCHES (apply these fixes in the next chain) ==="]
    for i, m in enumerate(matches, 1):
        out.append(f"\n[{i}] {m['id']}")
        out.append(f"    diagnosis: {m['diagnosis']}")
        out.append(f"    fix_placement: {m['fix_placement']}")
        if m.get("fix_snippet"):
            out.append("    fix_snippet:")
            out.append("      " + _json.dumps(m["fix_snippet"], indent=2).replace("\n", "\n      "))
        if m.get("notes"):
            out.append(f"    notes: {m['notes']}")
    return "\n".join(out) + "\n"
