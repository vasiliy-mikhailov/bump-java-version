THE REGIME: a platform BOM manages this module, and its build has a phase none of
the families above describes. quarkus-maven-plugin runs generate-code and build
inside this module's own build, so the first real error is often not a javac
error at all. It reads "[error]: Build step" and then a fully qualified processor
name and the method that threw.

THAT NAME IS THE CAMPAIGN. Four shapes are on record in this corpus.
io.quarkus.deployment.steps.ClassTransformingBuildStep and
io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveProcessor both
died on "Unsupported class file major version", 65 under a 21 target and 69 under
a 25 target: that is a bytecode reader too old for the new major, but the reader
is the ASM inside the platform's own deployment jars and no floor in the brief
moves it. io.quarkus.hibernate.orm.deployment.HibernateOrmProcessor failed
enhancing a PanacheEntity, which is the same jars doing the same thing one step
later. And
io.quarkiverse.quarkus.reactive.h2.client.deployment.ReactiveH2ClientProcessor
threw NoClassDefFoundError on
javax/enterprise/context/ApplicationScoped, which is the javax to jakarta split
reaching an extension the platform does not manage.

So a finished campaign here is usually one version and it is worth saying which:
the platform version for the first three, the extension's own for the fourth.
quarkus-social cleared major 65 by moving quarkus.platform.version from
2.12.0.Final to 3.16.4, and pagopa-gpd-payments-pull needed the platform and its
Quarkiverse extension moved together. Both then had to rename javax to jakarta
through their sources, which is what that crossing means and not a separate wall.
