fun foo() {
    while (true) {
        if (testSome()) {
            break
        }
    }
}

fun testSome(): Boolean {
    return false
}

// 2 LINENUMBER 2