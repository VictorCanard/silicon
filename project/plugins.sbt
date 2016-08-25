resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"

resolvers +=
  Resolver.url("bintray-mschwerhoff-sbt-plugins",
               url("https://dl.bintray.com/mschwerhoff/sbt-plugins/"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.2.0")

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.6.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.9.2")

addSbtPlugin("de.oakgrove" % "sbt-hgid" % "0.3")

addSbtPlugin("de.oakgrove" % "sbt-brand" % "0.3")
