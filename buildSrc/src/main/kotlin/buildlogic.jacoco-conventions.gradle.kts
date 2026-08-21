plugins {
    java
    jacoco
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    // XML as well as HTML: a coverage number nothing can read programmatically is a number nobody
    // checks. CI and tooling need the XML.
    reports {
        xml.required = true
        html.required = true
    }
}
