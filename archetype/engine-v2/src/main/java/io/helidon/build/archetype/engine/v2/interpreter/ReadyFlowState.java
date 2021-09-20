/*
 * Copyright (c) 2021 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.build.archetype.engine.v2.interpreter;

import java.util.Optional;

public class ReadyFlowState extends FlowState {

    private final Flow flow;
    private final OutputConverterVisitor outputConverterVisitor = new OutputConverterVisitor();

    ReadyFlowState(Flow flow) {
        this.flow = flow;
    }

    @Override
    Optional<Flow.Result> result() {
        return Optional.empty();
    }

    @Override
    void build(ContextAST context) {
        ASTNode lastNode = flow.interpreter().stack().peek();
        flow.interpreter().visit(context, lastNode);
        Flow.Result result = new Flow.Result();

        result.context().putAll(flow.interpreter().pathToContextNodeMap());

        flow.tree().forEach(step -> traverseTree(step, result));

        flow.state(new DoneFlowState(result));
    }

    private void traverseTree(ASTNode node, Flow.Result result) {
        if (node instanceof OutputAST) {
            result.outputs().add(node.accept(outputConverterVisitor, null));
        } else {
            node.children().forEach(child -> traverseTree((ASTNode) child, result));
        }
    }

    @Override
    FlowStateEnum type() {
        return FlowStateEnum.READY;
    }
}
