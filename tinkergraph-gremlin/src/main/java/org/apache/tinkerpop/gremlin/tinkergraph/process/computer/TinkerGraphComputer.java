/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.tinkergraph.process.computer;

import org.apache.tinkerpop.gremlin.process.computer.ComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.computer.MapReduce;
import org.apache.tinkerpop.gremlin.process.computer.VertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.util.ComputerGraph;
import org.apache.tinkerpop.gremlin.process.computer.util.DefaultComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.util.GraphComputerHelper;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerHelper;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public final class TinkerGraphComputer implements GraphComputer {

    private ResultGraph resultGraph = null;
    private Persist persist = null;

    private VertexProgram<?> vertexProgram;
    private final TinkerGraph graph;
    private TinkerMemory memory;
    private final TinkerMessageBoard messageBoard = new TinkerMessageBoard();
    private boolean executed = false;
    private final Set<MapReduce> mapReducers = new HashSet<>();

    public TinkerGraphComputer(final TinkerGraph graph) {
        this.graph = graph;
    }

    @Override
    public GraphComputer result(final ResultGraph resultGraph) {
        this.resultGraph = resultGraph;
        return this;
    }

    @Override
    public GraphComputer persist(final Persist persist) {
        this.persist = persist;
        return this;
    }

    @Override
    public GraphComputer program(final VertexProgram vertexProgram) {
        this.vertexProgram = vertexProgram;
        return this;
    }

    @Override
    public GraphComputer mapReduce(final MapReduce mapReduce) {
        this.mapReducers.add(mapReduce);
        return this;
    }

    @Override
    public Future<ComputerResult> submit() {
        // a graph computer can only be executed once
        if (this.executed)
            throw Exceptions.computerHasAlreadyBeenSubmittedAVertexProgram();
        else
            this.executed = true;
        // it is not possible execute a computer if it has no vertex program nor mapreducers
        if (null == this.vertexProgram && this.mapReducers.isEmpty())
            throw GraphComputer.Exceptions.computerHasNoVertexProgramNorMapReducers();
        // it is possible to run mapreducers without a vertex program
        if (null != this.vertexProgram) {
            GraphComputerHelper.validateProgramOnComputer(this, this.vertexProgram);
            this.mapReducers.addAll(this.vertexProgram.getMapReducers());
        }
        // get the result graph and persist state to use for the computation
        this.resultGraph = GraphComputerHelper.getResultGraphState(Optional.ofNullable(this.vertexProgram), Optional.ofNullable(this.resultGraph));
        this.persist = GraphComputerHelper.getPersistState(Optional.ofNullable(this.vertexProgram), Optional.ofNullable(this.persist));
        if (!this.features().supportsResultGraphPersistCombination(this.resultGraph, this.persist))
            throw GraphComputer.Exceptions.resultGraphPersistCombinationNotSupported(this.resultGraph, this.persist);

        // initialize the memory
        this.memory = new TinkerMemory(this.vertexProgram, this.mapReducers);
        return CompletableFuture.<ComputerResult>supplyAsync(() -> {
            final long time = System.currentTimeMillis();
            try (final TinkerWorkerPool workers = new TinkerWorkerPool(Runtime.getRuntime().availableProcessors())) {
                if (null != this.vertexProgram) {
                    TinkerHelper.createGraphComputerView(this.graph, this.vertexProgram.getElementComputeKeys());
                    // execute the vertex program
                    this.vertexProgram.setup(this.memory);
                    this.memory.completeSubRound();
                    while (true) {
                        workers.setVertexProgram(this.vertexProgram);
                        workers.vertexProgramWorkerIterationStart(this.memory.asImmutable());
                        final SynchronizedIterator<Vertex> vertices = new SynchronizedIterator<>(this.graph.vertices());
                        workers.executeVertexProgram(vertexProgram -> {
                            while (true) {
                                final Vertex vertex = vertices.next();
                                if (null == vertex) return;
                                vertexProgram.execute(
                                        ComputerGraph.vertexProgram(vertex, this.vertexProgram),
                                        new TinkerMessenger<>(vertex, this.messageBoard, vertexProgram.getMessageCombiner()),
                                        this.memory
                                );
                            }
                        });
                        workers.vertexProgramWorkerIterationEnd(this.memory.asImmutable());
                        this.messageBoard.completeIteration();
                        this.memory.completeSubRound();
                        if (this.vertexProgram.terminate(this.memory)) {
                            this.memory.incrIteration();
                            this.memory.completeSubRound();
                            break;
                        } else {
                            this.memory.incrIteration();
                            this.memory.completeSubRound();
                        }
                    }
                }

                // execute mapreduce jobs
                for (final MapReduce mapReduce : mapReducers) {
                    if (mapReduce.doStage(MapReduce.Stage.MAP)) {
                        final TinkerMapEmitter<?, ?> mapEmitter = new TinkerMapEmitter<>(mapReduce.doStage(MapReduce.Stage.REDUCE));
                        final SynchronizedIterator<Vertex> vertices = new SynchronizedIterator<>(this.graph.vertices());
                        workers.setMapReduce(mapReduce);
                        workers.mapReduceWorkerStart(MapReduce.Stage.MAP);
                        workers.executeMapReduce(workerMapReduce -> {
                            while (true) {
                                final Vertex vertex = vertices.next();
                                if (null == vertex) return;
                                workerMapReduce.map(ComputerGraph.mapReduce(vertex), mapEmitter);
                            }
                        });
                        workers.mapReduceWorkerEnd(MapReduce.Stage.MAP);

                        // sort results if a map output sort is defined
                        mapEmitter.complete(mapReduce);

                        // no need to run combiners as this is single machine
                        if (mapReduce.doStage(MapReduce.Stage.REDUCE)) {
                            final TinkerReduceEmitter<?, ?> reduceEmitter = new TinkerReduceEmitter<>();
                            final SynchronizedIterator<Map.Entry<?, Queue<?>>> keyValues = new SynchronizedIterator((Iterator) mapEmitter.reduceMap.entrySet().iterator());
                            workers.mapReduceWorkerStart(MapReduce.Stage.REDUCE);
                            workers.executeMapReduce(workerMapReduce -> {
                                while (true) {
                                    final Map.Entry<?, Queue<?>> entry = keyValues.next();
                                    if (null == entry) return;
                                    workerMapReduce.reduce(entry.getKey(), entry.getValue().iterator(), reduceEmitter);
                                }
                            });
                            workers.mapReduceWorkerEnd(MapReduce.Stage.REDUCE);
                            reduceEmitter.complete(mapReduce); // sort results if a reduce output sort is defined
                            mapReduce.addResultToMemory(this.memory, reduceEmitter.reduceQueue.iterator());
                        } else {
                            mapReduce.addResultToMemory(this.memory, mapEmitter.mapQueue.iterator());
                        }
                    }
                }
                // update runtime and return the newly computed graph
                this.memory.setRuntime(System.currentTimeMillis() - time);
                this.memory.complete();
                // determine the resultant graph based on the result graph/persist state
                final TinkerGraphComputerView view = TinkerHelper.getGraphComputerView(this.graph);
                final Graph resultGraph = null == view ? this.graph : view.processResultGraphPersist(this.resultGraph, this.persist);
                TinkerHelper.dropGraphComputerView(this.graph);
                return new DefaultComputerResult(resultGraph, this.memory.asImmutable());

            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Override
    public String toString() {
        return StringFactory.graphComputerString(this);
    }

    private static class SynchronizedIterator<V> {

        private final Iterator<V> iterator;

        public SynchronizedIterator(final Iterator<V> iterator) {
            this.iterator = iterator;
        }

        public synchronized V next() {
            return this.iterator.hasNext() ? this.iterator.next() : null;
        }
    }

    @Override
    public Features features() {
        return new Features() {

            public boolean supportsVertexAddition() {
                return false;
            }

            public boolean supportsVertexRemoval() {
                return false;
            }

            public boolean supportsVertexPropertyRemoval() {
                return false;
            }

            public boolean supportsEdgeAddition() {
                return false;
            }

            public boolean supportsEdgeRemoval() {
                return false;
            }

            public boolean supportsEdgePropertyAddition() {
                return false;
            }

            public boolean supportsEdgePropertyRemoval() {
                return false;
            }
        };
    }
}