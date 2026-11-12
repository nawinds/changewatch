plugins { kotlin("jvm") }
kotlin { jvmToolchain(25) }
dependencies { implementation(project(":core")) }
