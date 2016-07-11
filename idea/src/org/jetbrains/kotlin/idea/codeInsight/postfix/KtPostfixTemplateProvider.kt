/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.codeInsight.postfix

import com.intellij.codeInsight.template.postfix.templates.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.intentions.getExpressionsByCursorContext
import org.jetbrains.kotlin.idea.intentions.negate
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isBoolean


class KtPostfixTemplateProvider : PostfixTemplateProvider {
    override fun getTemplates() = setOf<PostfixTemplate>(KtNotPostfixTemplate)

    override fun isTerminalSymbol(currentChar: Char) = currentChar == '.' || currentChar == '!'

    override fun afterExpand(file: PsiFile, editor: Editor) {
    }

    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int) = copyFile

    override fun preExpand(file: PsiFile, editor: Editor) {
    }
}

private object KtNotPostfixTemplate : NotPostfixTemplate(
        KtPostfixTemplatePsiInfo,
        BOOLEAN_EXPRESSION_POSTFIX_TEMPLATE_SELECTOR
)

private object KtPostfixTemplatePsiInfo : PostfixTemplatePsiInfo() {
    override fun createExpression(context: PsiElement, prefix: String, suffix: String) =
            KtPsiFactory(context.project).createExpression(prefix + context.text + suffix)

    override fun getNegatedExpression(element: PsiElement) = (element as KtExpression).negate()
}

internal val BOOLEAN_EXPRESSION_POSTFIX_TEMPLATE_SELECTOR = createFilteredByTypeSelector { it.isBoolean() }
internal val ANY_EXPRESSION: PostfixTemplateExpressionSelector = KtExpressionPostfixTemplateSelector(null)

internal fun createFilteredByTypeSelector(predicate: (KotlinType) -> Boolean): PostfixTemplateExpressionSelectorBase =
        KtExpressionPostfixTemplateSelector(
                Condition {
                    it is KtExpression && it.getType(it.analyze(BodyResolveMode.PARTIAL_FOR_COMPLETION))?.let { predicate(it) } ?: false
                })

private class KtExpressionPostfixTemplateSelector(
        val filter: Condition<PsiElement>?
) : PostfixTemplateExpressionSelectorBase(Condition { it is KtExpression }) {
    override fun getNonFilteredExpressions(
            context: PsiElement,
            document: Document,
            offset: Int
    ) = getExpressionsByCursorContext(context).toList()

    @Suppress("UNCHECKED_CAST")
    override fun getFilters(offset: Int): Condition<PsiElement> = filter ?: Condition.TRUE as Condition<PsiElement>
}
