plugins {
    id("lib-multisrc")
}

baseVersionCode = 36

dependencies {
    api(project(":lib:cryptoaes"))
    api(project(":lib:i18n"))
}
