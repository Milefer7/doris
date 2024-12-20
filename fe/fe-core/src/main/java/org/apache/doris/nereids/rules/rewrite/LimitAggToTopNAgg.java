// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.rewrite;

import org.apache.doris.nereids.properties.OrderKey;
import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalAggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalLimit;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalTopN;
import org.apache.doris.qe.ConnectContext;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * convert limit->agg to topn->agg
 * if all group keys are in limit.output
 * to enable
 * 1. topn-filter
 * 2. push limit to local agg
 */
public class LimitAggToTopNAgg implements RewriteRuleFactory {
    @Override
    public List<Rule> buildRules() {
        return ImmutableList.of(
                // limit -> agg to topn->agg
                logicalLimit(logicalAggregate())
                        .when(limit -> ConnectContext.get() != null
                                && ConnectContext.get().getSessionVariable().pushTopnToAgg
                                && ConnectContext.get().getSessionVariable().topnOptLimitThreshold
                                >= limit.getLimit() + limit.getOffset())
                        .then(limit -> {
                            LogicalAggregate<? extends Plan> agg = limit.child();
                            Optional<OrderKey> orderKeysOpt = tryGenerateOrderKeyByTheFirstGroupKey(agg);
                            if (!orderKeysOpt.isPresent()) {
                                return null;
                            }
                            List<OrderKey> orderKeys = Lists.newArrayList(orderKeysOpt.get());
                            return new LogicalTopN<>(orderKeys, limit.getLimit(), limit.getOffset(), agg);
                        }).toRule(RuleType.LIMIT_AGG_TO_TOPN_AGG),
                //limit->project->agg to topn->project->agg
                logicalLimit(logicalProject(logicalAggregate()))
                        .when(limit -> ConnectContext.get() != null
                                && ConnectContext.get().getSessionVariable().pushTopnToAgg
                                && ConnectContext.get().getSessionVariable().topnOptLimitThreshold
                                >= limit.getLimit() + limit.getOffset())
                        .then(limit -> {
                            LogicalProject<? extends Plan> project = limit.child();
                            LogicalAggregate<? extends Plan> agg
                                    = (LogicalAggregate<? extends Plan>) project.child();
                            Optional<OrderKey> orderKeysOpt = tryGenerateOrderKeyByTheFirstGroupKey(agg);
                            if (!orderKeysOpt.isPresent()) {
                                return null;
                            }
                            List<OrderKey> orderKeys = Lists.newArrayList(orderKeysOpt.get());
                            Plan result;

                            if (outputAllGroupKeys(limit, agg)) {
                                result = new LogicalTopN<>(orderKeys, limit.getLimit(),
                                        limit.getOffset(), project);
                            } else {
                                // add the first group by key to topn, and prune this key by upper project
                                // topn order keys are prefix of group by keys
                                // refer to PushTopnToAgg.tryGenerateOrderKeyByGroupKeyAndTopnKey()
                                Expression firstGroupByKey = agg.getGroupByExpressions().get(0);
                                if (!(firstGroupByKey instanceof SlotReference)) {
                                    return null;
                                }
                                boolean shouldPruneFirstGroupByKey = true;
                                if (project.getOutputs().contains(firstGroupByKey)) {
                                    shouldPruneFirstGroupByKey = false;
                                } else {
                                    List<NamedExpression> bottomProjections = Lists.newArrayList(project.getProjects());
                                    bottomProjections.add((SlotReference) firstGroupByKey);
                                    project = project.withProjects(bottomProjections);
                                }
                                LogicalTopN topn = new LogicalTopN<>(orderKeys, limit.getLimit(),
                                        limit.getOffset(), project);
                                if (shouldPruneFirstGroupByKey) {
                                    List<NamedExpression> limitOutput = limit.getOutput().stream()
                                            .map(e -> (NamedExpression) e).collect(Collectors.toList());
                                    result = new LogicalProject<>(limitOutput, topn);
                                } else {
                                    result = topn;
                                }
                            }
                            return result;
                        }).toRule(RuleType.LIMIT_AGG_TO_TOPN_AGG),
                // topn -> agg: add all group key to sort key, if sort key is prefix of group key
                logicalTopN(logicalAggregate())
                        .when(topn -> ConnectContext.get() != null
                                && ConnectContext.get().getSessionVariable().pushTopnToAgg
                                && ConnectContext.get().getSessionVariable().topnOptLimitThreshold
                                >= topn.getLimit() + topn.getOffset())
                        .then(topn -> {
                            LogicalAggregate<? extends Plan> agg = (LogicalAggregate<? extends Plan>) topn.child();
                            List<OrderKey> newOrders = tryGenerateOrderKeyByGroupKeyAndTopnKey(topn, agg);
                            if (newOrders.isEmpty()) {
                                return topn;
                            } else {
                                return topn.withOrderKeys(newOrders);
                            }
                        }).toRule(RuleType.LIMIT_AGG_TO_TOPN_AGG));
    }

    private List<OrderKey> tryGenerateOrderKeyByGroupKeyAndTopnKey(LogicalTopN<? extends Plan> topN,
            LogicalAggregate<? extends Plan> agg) {
        List<OrderKey> orderKeys = Lists.newArrayListWithCapacity(agg.getGroupByExpressions().size());
        if (topN.getOrderKeys().size() > agg.getGroupByExpressions().size()) {
            return orderKeys;
        }
        List<Expression> topnKeys = topN.getOrderKeys().stream()
                .map(OrderKey::getExpr).collect(Collectors.toList());
        for (int i = 0; i < topN.getOrderKeys().size(); i++) {
            // prefix check
            if (!topnKeys.get(i).equals(agg.getGroupByExpressions().get(i))) {
                return Lists.newArrayList();
            }
            orderKeys.add(topN.getOrderKeys().get(i));
        }
        for (int i = topN.getOrderKeys().size(); i < agg.getGroupByExpressions().size(); i++) {
            orderKeys.add(new OrderKey(agg.getGroupByExpressions().get(i), true, false));
        }
        return orderKeys;
    }

    private boolean outputAllGroupKeys(LogicalLimit limit, LogicalAggregate agg) {
        return limit.getOutputSet().containsAll(agg.getGroupByExpressions());
    }

    private Optional<OrderKey> tryGenerateOrderKeyByTheFirstGroupKey(LogicalAggregate<? extends Plan> agg) {
        if (agg.getGroupByExpressions().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OrderKey(agg.getGroupByExpressions().get(0), true, false));
    }
}
