/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.cfg;

import com.google.common.collect.Sets;
import org.jetbrains.kotlin.psi.KtElement;

import java.util.Collections;
import java.util.Set;

public class BreakableBlockInfo extends BlockInfo {
    private final KtElement element;
    private final Label entryPoint;
    private final Label exitPoint;
    private final Set<Label> referablePoints = Sets.newHashSet();

    public BreakableBlockInfo(KtElement element, Label entryPoint, Label exitPoint) {
        this.element = element;
        this.entryPoint = entryPoint;
        this.exitPoint = exitPoint;
        markReferablePoints(entryPoint, exitPoint);
    }

    protected void markReferablePoints(Label... labels) {
        Collections.addAll(referablePoints, labels);
    }

    public KtElement getElement() {
        return element;
    }

    public Label getEntryPoint() {
        return entryPoint;
    }

    public Label getExitPoint() {
        return exitPoint;
    }

    public Set<Label> getReferablePoints() {
        return referablePoints;
    }
}
