// "Change to property access" "true"

fun x() {
    val y = (1 + 2<caret>)()
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.UnresolvedInvocationQuickFix$ChangeToPropertyAccessQuickFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.UnresolvedInvocationQuickFix$ChangeToPropertyAccessQuickFix