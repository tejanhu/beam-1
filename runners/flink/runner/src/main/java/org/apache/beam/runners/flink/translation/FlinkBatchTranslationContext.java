/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.flink.translation;

import org.apache.beam.runners.flink.translation.types.CoderTypeInformation;
import org.apache.beam.runners.flink.translation.types.KvCoderTypeInformation;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.KvCoder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.PInput;
import com.google.cloud.dataflow.sdk.values.POutput;
import com.google.cloud.dataflow.sdk.values.PValue;
import com.google.cloud.dataflow.sdk.values.TypedPValue;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;

import java.util.HashMap;
import java.util.Map;

public class FlinkBatchTranslationContext {
  
  private final Map<PValue, DataSet<?>> dataSets;
  private final Map<PCollectionView<?>, DataSet<?>> broadcastDataSets;

  private final ExecutionEnvironment env;
  private final PipelineOptions options;

  private AppliedPTransform<?, ?, ?> currentTransform;
  
  // ------------------------------------------------------------------------
  
  public FlinkBatchTranslationContext(ExecutionEnvironment env, PipelineOptions options) {
    this.env = env;
    this.options = options;
    this.dataSets = new HashMap<>();
    this.broadcastDataSets = new HashMap<>();
  }
  
  // ------------------------------------------------------------------------
  
  public ExecutionEnvironment getExecutionEnvironment() {
    return env;
  }

  public PipelineOptions getPipelineOptions() {
    return options;
  }
  
  @SuppressWarnings("unchecked")
  public <T> DataSet<T> getInputDataSet(PValue value) {
    return (DataSet<T>) dataSets.get(value);
  }

  public void setOutputDataSet(PValue value, DataSet<?> set) {
    if (!dataSets.containsKey(value)) {
      dataSets.put(value, set);
    }
  }

  /**
   * Sets the AppliedPTransform which carries input/output.
   * @param currentTransform
   */
  public void setCurrentTransform(AppliedPTransform<?, ?, ?> currentTransform) {
    this.currentTransform = currentTransform;
  }

  @SuppressWarnings("unchecked")
  public <T> DataSet<T> getSideInputDataSet(PCollectionView<?> value) {
    return (DataSet<T>) broadcastDataSets.get(value);
  }

  public void setSideInputDataSet(PCollectionView<?> value, DataSet<?> set) {
    if (!broadcastDataSets.containsKey(value)) {
      broadcastDataSets.put(value, set);
    }
  }
  
  @SuppressWarnings("unchecked")
  public <T> TypeInformation<T> getTypeInfo(PInput output) {
    if (output instanceof TypedPValue) {
      Coder<?> outputCoder = ((TypedPValue) output).getCoder();
      if (outputCoder instanceof KvCoder) {
        return new KvCoderTypeInformation((KvCoder) outputCoder);
      } else {
        return new CoderTypeInformation(outputCoder);
      }
    }
    return new GenericTypeInfo<>((Class<T>)Object.class);
  }

  public <T> TypeInformation<T> getInputTypeInfo() {
    return getTypeInfo(currentTransform.getInput());
  }

  public <T> TypeInformation<T> getOutputTypeInfo() {
    return getTypeInfo((PValue) currentTransform.getOutput());
  }

  @SuppressWarnings("unchecked")
  <I extends PInput> I getInput(PTransform<I, ?> transform) {
    return (I) currentTransform.getInput();
  }

  @SuppressWarnings("unchecked")
  <O extends POutput> O getOutput(PTransform<?, O> transform) {
    return (O) currentTransform.getOutput();
  }
}
