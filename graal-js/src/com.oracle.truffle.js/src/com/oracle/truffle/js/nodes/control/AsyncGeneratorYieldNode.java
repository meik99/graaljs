/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.nodes.control;

import java.util.Set;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.GetIteratorNode;
import com.oracle.truffle.js.nodes.access.GetMethodNode;
import com.oracle.truffle.js.nodes.access.IteratorCompleteNode;
import com.oracle.truffle.js.nodes.access.IteratorNextNode;
import com.oracle.truffle.js.nodes.access.IteratorValueNode;
import com.oracle.truffle.js.nodes.access.JSReadFrameSlotNode;
import com.oracle.truffle.js.nodes.access.WriteNode;
import com.oracle.truffle.js.nodes.control.ReturnNode.FrameReturnNode;
import com.oracle.truffle.js.nodes.control.YieldResultNode.ExceptionYieldResultNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.UserScriptException;
import com.oracle.truffle.js.runtime.objects.Completion;
import com.oracle.truffle.js.runtime.objects.IteratorRecord;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.Undefined;

public class AsyncGeneratorYieldNode extends AbstractAwaitNode implements ResumableNode.WithIntState {
    @Child protected ReturnNode returnNode;
    @Child private YieldResultNode generatorYieldNode;

    protected AsyncGeneratorYieldNode(JSContext context, int stateSlot, JavaScriptNode expression,
                    JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readYieldResultNode, ReturnNode returnNode) {
        super(context, stateSlot, expression, readAsyncContextNode, readYieldResultNode);
        this.returnNode = returnNode;
        this.generatorYieldNode = new ExceptionYieldResultNode();
    }

    public static AsyncGeneratorYieldNode createYield(JSContext context, int stateSlot, JavaScriptNode expression,
                    JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readAsyncResultNode, ReturnNode returnNode) {
        return new AsyncGeneratorYieldNode(context, stateSlot, expression, readAsyncContextNode, readAsyncResultNode, returnNode);
    }

    public static AsyncGeneratorYieldNode createYieldStar(JSContext context, int stateSlot, JavaScriptNode expression,
                    JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readAsyncResultNode, ReturnNode returnNode, int iteratorTempSlot) {
        return new AsyncGeneratorYieldStarNode(context, expression, stateSlot, readAsyncContextNode, readAsyncResultNode, returnNode, iteratorTempSlot);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        int state = getStateAsInt(frame, stateSlot);
        // 0 .. execute expression and await
        // 1 .. resume await and yield
        // 2 .. resume yield (and await if return)
        // 3 .. resume await and return
        final int awaitValue = 1;
        final int suspendedYield = 2;
        final int awaitResumptionValue = 3;

        if (state == 0) {
            Object value = expression.execute(frame);
            setStateAsInt(frame, stateSlot, awaitValue);
            return suspendAwait(frame, value);
        } else if (state == awaitValue) {
            Object awaited = resumeAwait(frame);
            setStateAsInt(frame, stateSlot, suspendedYield);
            return suspendYield(frame, awaited);
        } else {
            assert state >= suspendedYield;
            setStateAsInt(frame, stateSlot, 0);
            if (state == suspendedYield) {
                Completion completion = resumeYield(frame);
                if (completion.isNormal()) {
                    return completion.getValue();
                } else if (completion.isThrow()) {
                    throw UserScriptException.create(completion.getValue(), this, context.getContextOptions().getStackTraceLimit());
                } else {
                    assert completion.isReturn();
                    // Let awaited be Await(resumptionValue.[[Value]]).
                    setStateAsInt(frame, stateSlot, awaitResumptionValue);
                    return suspendAwait(frame, completion.getValue());
                }
            } else {
                assert state == awaitResumptionValue;
                // If awaited.[[Type]] is throw return Completion(awaited).
                Object awaited = resumeAwait(frame);
                // Assert: awaited.[[Type]] is normal.
                return returnValue(frame, awaited);
            }
        }
    }

    protected final Object suspendYield(VirtualFrame frame, Object awaited) {
        return generatorYieldNode.generatorYield(frame, awaited);
    }

    protected final Completion resumeYield(VirtualFrame frame) {
        return (Completion) readAsyncResultNode.execute(frame);
    }

    protected final Object returnValue(VirtualFrame frame, Object value) {
        assert getStateAsInt(frame, stateSlot) == 0;
        if (returnNode instanceof FrameReturnNode) {
            ((WriteNode) returnNode.expression).executeWrite(frame, value);
        }
        throw new ReturnException(value);
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return createYield(context, stateSlot, cloneUninitialized(expression, materializedTags),
                        cloneUninitialized(readAsyncContextNode, materializedTags), cloneUninitialized(readAsyncResultNode, materializedTags), cloneUninitialized(returnNode, materializedTags));
    }
}

class AsyncGeneratorYieldStarNode extends AsyncGeneratorYieldNode {

    private final int iteratorTempSlot;

    @Child private GetIteratorNode getIteratorNode;
    @Child private IteratorNextNode iteratorNextNode;
    @Child private IteratorCompleteNode iteratorCompleteNode;
    @Child private IteratorValueNode iteratorValueNode;
    @Child private GetMethodNode getThrowMethodNode;
    @Child private GetMethodNode getReturnMethodNode;
    @Child private JSFunctionCallNode callThrowNode;
    @Child private JSFunctionCallNode callReturnNode;
    private final BranchProfile throwMethodMissingBranch = BranchProfile.create();

    protected AsyncGeneratorYieldStarNode(JSContext context, JavaScriptNode expression, int stateSlot,
                    JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readYieldResultNode, ReturnNode returnNode, int iteratorTempSlot) {
        super(context, stateSlot, expression, readAsyncContextNode, readYieldResultNode, returnNode);
        this.iteratorTempSlot = iteratorTempSlot;

        this.getIteratorNode = GetIteratorNode.createAsync(context, null);
        this.iteratorNextNode = IteratorNextNode.create();
        this.iteratorCompleteNode = IteratorCompleteNode.create(context);
        this.iteratorValueNode = IteratorValueNode.create();
        this.getThrowMethodNode = GetMethodNode.create(context, Strings.THROW);
        this.getReturnMethodNode = GetMethodNode.create(context, Strings.RETURN);
        this.callThrowNode = JSFunctionCallNode.createCall();
        this.callReturnNode = JSFunctionCallNode.createCall();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        int state = getStateAsInt(frame, stateSlot);
        final int loopBegin = 1;
        final int normalOrThrowAwaitInnerResult = 2;
        final int returnAwaitInnerReturnResult = 3;
        final int asyncGeneratorYieldInnerResult = 4;
        final int asyncGeneratorYieldInnerResultSuspendedYield = 5;
        final int asyncGeneratorYieldInnerResultReturn = 6;
        final int returnAwaitReceivedValue = 7;
        final int throwAwaitReturnResult = 8;

        IteratorRecord iteratorRecord;
        if (state == 0) {
            iteratorRecord = getIteratorNode.execute(expression.execute(frame));
            frame.setObject(iteratorTempSlot, iteratorRecord);
            state = loopBegin;
        } else {
            iteratorRecord = (IteratorRecord) frame.getObject(iteratorTempSlot);
        }
        JSDynamicObject iterator = iteratorRecord.getIterator();

        Completion received = Completion.forNormal(Undefined.instance);
        for (;;) {
            switch (state) {
                case loopBegin: {
                    if (received.isNormal()) {
                        Object innerResult = iteratorNextNode.execute(iteratorRecord, received.getValue());
                        awaitWithNext(frame, innerResult, normalOrThrowAwaitInnerResult);
                    } else if (received.isThrow()) {
                        Object throwMethod = getThrowMethodNode.executeWithTarget(iterator);
                        if (throwMethod != Undefined.instance) {
                            Object innerResult = callThrowMethod(throwMethod, iterator, received.getValue());
                            awaitWithNext(frame, innerResult, normalOrThrowAwaitInnerResult);
                            /*
                             * NOTE: Exceptions from the inner iterator throw method are propagated.
                             * Normal completions from an inner throw method are processed similarly
                             * to an inner next.
                             */
                        } else {
                            /*
                             * NOTE: If iterator does not have a throw method, this throw is going
                             * to terminate the yield* loop. But first we need to give iterator a
                             * chance to clean up.
                             */
                            throwMethodMissingBranch.enter();
                            // AsyncIteratorClose
                            Object returnMethod = getReturnMethodNode.executeWithTarget(iterator);
                            error: if (returnMethod != Undefined.instance) {
                                Object returnResult;
                                try {
                                    returnResult = callReturnNode.executeCall(JSArguments.createZeroArg(iterator, returnMethod));
                                } catch (AbstractTruffleException e) {
                                    // swallow inner error
                                    break error;
                                }
                                awaitWithNext(frame, returnResult, throwAwaitReturnResult);
                            }
                            throw Errors.createTypeErrorYieldStarThrowMethodMissing(this);
                        }
                    } else {
                        assert received.isReturn();
                        Object returnMethod = getReturnMethodNode.executeWithTarget(iterator);
                        if (returnMethod != Undefined.instance) {
                            Object innerReturnResult = callReturnMethod(returnMethod, iterator, received.getValue());
                            awaitWithNext(frame, innerReturnResult, returnAwaitInnerReturnResult);
                        } else {
                            awaitWithNext(frame, received.getValue(), returnAwaitReceivedValue);
                        }
                    }
                    break;
                }

                // received.[[Type]] is normal or throw
                case normalOrThrowAwaitInnerResult: {
                    Object awaited = resumeAwait(frame);
                    JSDynamicObject innerResult = checkcastIterResult(awaited);
                    boolean done = iteratorCompleteNode.execute(innerResult);
                    if (done) {
                        reset(frame);
                        return iteratorValueNode.execute(innerResult);
                    }
                    Object iteratorValue = iteratorValueNode.execute(innerResult);
                    awaitWithNext(frame, iteratorValue, asyncGeneratorYieldInnerResult);
                    break;
                }
                // received.[[Type]] is return
                case returnAwaitInnerReturnResult: {
                    Object awaited = resumeAwait(frame);
                    JSDynamicObject innerReturnResult = checkcastIterResult(awaited);
                    boolean done = iteratorCompleteNode.execute(innerReturnResult);
                    if (done) {
                        reset(frame);
                        return returnValue(frame, iteratorValueNode.execute(innerReturnResult));
                    }
                    Object iteratorValue = iteratorValueNode.execute(innerReturnResult);
                    awaitWithNext(frame, iteratorValue, asyncGeneratorYieldInnerResult);
                    break;
                }

                // received.[[Type]] is normal, throw, or return
                // AsyncGeneratorYield, then repeat
                case asyncGeneratorYieldInnerResult: {
                    Object awaited = resumeAwait(frame);
                    yieldWithNext(frame, awaited, asyncGeneratorYieldInnerResultSuspendedYield);
                    break;
                }
                case asyncGeneratorYieldInnerResultSuspendedYield: {
                    Completion resumptionValue = resumeYield(frame);
                    if (!resumptionValue.isReturn()) {
                        received = resumptionValue;
                        state = loopBegin; // repeat
                        break;
                    } else {
                        assert resumptionValue.isReturn();
                        awaitWithNext(frame, resumptionValue.getValue(), asyncGeneratorYieldInnerResultReturn);
                        break;
                    }
                }
                case asyncGeneratorYieldInnerResultReturn: {
                    Completion returnValue = resumeYield(frame);
                    if (returnValue.isNormal()) {
                        received = Completion.forReturn(returnValue.getValue());
                    } else {
                        assert returnValue.isThrow();
                        received = returnValue;
                    }
                    state = loopBegin; // repeat
                    break;
                }

                // received.[[Type]] is return, return method is undefined
                case returnAwaitReceivedValue: {
                    Object awaited = resumeAwait(frame);
                    reset(frame);
                    return returnValue(frame, awaited);
                }
                // received.[[Type]] is throw, throw method is undefined
                case throwAwaitReturnResult: {
                    throwMethodMissingBranch.enter();
                    // AsyncIteratorClose: handle Await(innerResult) throw completion.
                    resumeAwait(frame);
                    throw Errors.createTypeErrorYieldStarThrowMethodMissing(this);
                }
                default:
                    throw Errors.shouldNotReachHere();
            }
            // Either suspend and resume into another state or repeat from the beginning.
            assert state == loopBegin;
        }
    }

    private void awaitWithNext(VirtualFrame frame, Object value, int nextState) {
        setStateAsInt(frame, stateSlot, nextState);
        suspendAwait(frame, value);
    }

    private Object yieldWithNext(VirtualFrame frame, Object value, int nextState) {
        setStateAsInt(frame, stateSlot, nextState);
        return suspendYield(frame, value);
    }

    private void reset(VirtualFrame frame) {
        setStateAsInt(frame, stateSlot, 0);
        frame.setObject(iteratorTempSlot, Undefined.instance);
    }

    private Object callThrowMethod(Object throwMethod, JSDynamicObject iterator, Object received) {
        return callThrowNode.executeCall(JSArguments.createOneArg(iterator, throwMethod, received));
    }

    private Object callReturnMethod(Object returnMethod, JSDynamicObject iterator, Object received) {
        return callReturnNode.executeCall(JSArguments.createOneArg(iterator, returnMethod, received));
    }

    private JSDynamicObject checkcastIterResult(Object iterResult) {
        if (!JSRuntime.isObject(iterResult)) {
            throw Errors.createTypeErrorIterResultNotAnObject(iterResult, this);
        }
        return (JSDynamicObject) iterResult;
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return createYieldStar(context, stateSlot, cloneUninitialized(expression, materializedTags),
                        cloneUninitialized(readAsyncContextNode, materializedTags), cloneUninitialized(readAsyncResultNode, materializedTags), cloneUninitialized(returnNode, materializedTags),
                        iteratorTempSlot);
    }
}
