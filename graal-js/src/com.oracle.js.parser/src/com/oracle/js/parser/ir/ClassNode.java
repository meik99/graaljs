/*
 * Copyright (c) 2015, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.oracle.js.parser.ir;

import java.util.List;

import com.oracle.js.parser.ParserStrings;
import com.oracle.js.parser.ir.visitor.NodeVisitor;
import com.oracle.js.parser.ir.visitor.TranslatorNodeVisitor;
import com.oracle.truffle.api.strings.TruffleString;

/**
 * IR representation for class definitions.
 */
public class ClassNode extends LexicalContextExpression implements LexicalContextScope {
    private final IdentNode ident;
    private final Expression classHeritage;
    private final ClassElement constructor;
    private final List<ClassElement> classElements;
    private final List<Expression> classDecorators;
    private final Scope scope;
    private final int instanceFieldCount;
    private final int staticElementCount;
    private final boolean hasPrivateMethods;
    private final boolean hasPrivateInstanceMethods;

    public static final TruffleString PRIVATE_CONSTRUCTOR_BINDING_NAME = ParserStrings.constant("#constructor");

    /**
     * Constructor.
     *
     * @param token token
     * @param finish finish
     */
    public ClassNode(final long token, final int finish, final IdentNode ident, final Expression classHeritage, final ClassElement constructor, final List<ClassElement> classElements,
                    final List<Expression> classDecorators,
                    final Scope scope, final int instanceFieldCount, final int staticElementCount, final boolean hasPrivateMethods, final boolean hasPrivateInstanceMethods) {
        super(token, finish);
        this.ident = ident;
        this.classHeritage = classHeritage;
        this.constructor = constructor;
        this.classElements = List.copyOf(classElements);
        this.scope = scope;
        this.instanceFieldCount = instanceFieldCount;
        this.staticElementCount = staticElementCount;
        this.hasPrivateMethods = hasPrivateMethods;
        this.hasPrivateInstanceMethods = hasPrivateInstanceMethods;
        this.classDecorators = classDecorators;
        assert instanceFieldCount == elementCount(classElements, false);
        assert staticElementCount == elementCount(classElements, true);
    }

    private ClassNode(final ClassNode classNode, final IdentNode ident, final Expression classHeritage, final ClassElement constructor, final List<ClassElement> classElements,
                    final List<Expression> classDecorators) {
        super(classNode);
        this.ident = ident;
        this.classHeritage = classHeritage;
        this.constructor = constructor;
        this.classElements = List.copyOf(classElements);
        this.scope = classNode.scope;
        this.instanceFieldCount = elementCount(classElements, false);
        this.staticElementCount = elementCount(classElements, true);
        this.hasPrivateMethods = classNode.hasPrivateMethods;
        this.hasPrivateInstanceMethods = classNode.hasPrivateInstanceMethods;
        this.classDecorators = classDecorators;
    }

    private static int elementCount(List<ClassElement> classElements, boolean isStatic) {
        int count = 0;
        for (ClassElement classElement : classElements) {
            if (classElement.isStatic() == isStatic && (classElement.isClassField() || classElement.isClassStaticBlock())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Class identifier. Optional.
     */
    public IdentNode getIdent() {
        return ident;
    }

    private ClassNode setIdent(final IdentNode ident) {
        if (this.ident == ident) {
            return this;
        }
        return new ClassNode(this, ident, classHeritage, constructor, classElements, classDecorators);
    }

    /**
     * The expression of the {@code extends} clause. Optional.
     */
    public Expression getClassHeritage() {
        return classHeritage;
    }

    private ClassNode setClassHeritage(final Expression classHeritage) {
        if (this.classHeritage == classHeritage) {
            return this;
        }
        return new ClassNode(this, ident, classHeritage, constructor, classElements, classDecorators);
    }

    /**
     * Get the constructor method definition.
     */
    public ClassElement getConstructor() {
        return constructor;
    }

    public ClassNode setConstructor(final ClassElement constructor) {
        if (this.constructor == constructor) {
            return this;
        }
        return new ClassNode(this, ident, classHeritage, constructor, classElements, classDecorators);
    }

    /**
     * Get method definitions except the constructor.
     */
    public List<ClassElement> getClassElements() {
        return classElements;
    }

    public ClassNode setClassElements(final List<ClassElement> classElements) {
        if (this.classElements == classElements) {
            return this;
        }
        return new ClassNode(this, ident, classHeritage, constructor, classElements, classDecorators);
    }

    /**
     * Decorators.
     */
    public List<Expression> getDecorators() {
        return classDecorators;
    }

    public ClassNode setDecorators(final List<Expression> decorators) {
        if (this.classDecorators == decorators) {
            return this;
        }
        return new ClassNode(this, ident, classHeritage, constructor, classElements, classDecorators);
    }

    @Override
    public Node accept(final LexicalContext lc, final NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterClassNode(this)) {
            IdentNode newIdent = ident == null ? null : (IdentNode) ident.accept(visitor);
            Expression newClassHeritage = classHeritage == null ? null : (Expression) classHeritage.accept(visitor);
            ClassElement newConstructor = constructor == null ? null : (ClassElement) constructor.accept(visitor);
            List<ClassElement> newClassElements = Node.accept(visitor, classElements);
            List<Expression> newDecorators = classDecorators == null ? null : Node.accept(visitor, classDecorators);
            return visitor.leaveClassNode(setIdent(newIdent).setClassHeritage(newClassHeritage).setConstructor(newConstructor).setClassElements(newClassElements).setDecorators(newDecorators));
        }

        return this;
    }

    @Override
    public <R> R accept(final LexicalContext lc, TranslatorNodeVisitor<? extends LexicalContext, R> visitor) {
        return visitor.enterClassNode(this);
    }

    @Override
    public Scope getScope() {
        return scope;
    }

    public Scope getClassHeadScope() {
        return scope.isClassBodyScope() ? scope.getParent() : scope;
    }

    public boolean hasInstanceFields() {
        return instanceFieldCount != 0;
    }

    public int getInstanceFieldCount() {
        return instanceFieldCount;
    }

    public boolean hasStaticElements() {
        return staticElementCount != 0;
    }

    public int getStaticElementCount() {
        return staticElementCount;
    }

    public boolean hasPrivateMethods() {
        return hasPrivateMethods;
    }

    public boolean hasPrivateInstanceMethods() {
        return hasPrivateInstanceMethods;
    }

    public boolean isAnonymous() {
        return getIdent() == null;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        if (classDecorators != null) {
            for (Expression decorator : classDecorators) {
                sb.append("@");
                decorator.toString(sb, printType);
                sb.append(" ");
            }
        }
        sb.append("class");
        if (ident != null) {
            sb.append(' ');
            ident.toString(sb, printType);
        }
        if (classHeritage != null) {
            sb.append(" extends ");
            classHeritage.toString(sb, printType);
        }
        sb.append(" {");
        if (constructor != null) {
            constructor.toString(sb, printType);
        }
        for (ClassElement classElement : getClassElements()) {
            sb.append(", ");
            classElement.toString(sb, printType);
        }
        sb.append("}");
    }
}
