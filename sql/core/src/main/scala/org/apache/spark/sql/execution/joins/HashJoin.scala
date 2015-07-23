/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.joins

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.util.collection.CompactBuffer


trait HashJoin {
  self: SparkPlan =>

  val leftKeys: Seq[Expression]
  val rightKeys: Seq[Expression]
  val buildSide: BuildSide
  val left: SparkPlan
  val right: SparkPlan

  protected lazy val (buildPlan, streamedPlan) = buildSide match {
    case BuildLeft => (left, right)
    case BuildRight => (right, left)
  }

  protected lazy val (buildKeys, streamedKeys) = buildSide match {
    case BuildLeft => (leftKeys, rightKeys)
    case BuildRight => (rightKeys, leftKeys)
  }

  override def output: Seq[Attribute] = left.output ++ right.output

  protected[this] def supportUnsafe: Boolean = {
    (self.codegenEnabled && UnsafeProjection.canSupport(buildKeys)
      && UnsafeProjection.canSupport(self.schema))
  }

  override def outputsUnsafeRows: Boolean = supportUnsafe
  override def canProcessUnsafeRows: Boolean = supportUnsafe

  @transient protected lazy val streamSideKeyGenerator: Projection =
    if (supportUnsafe) {
      UnsafeProjection.create(streamedKeys, streamedPlan.output)
    } else {
      newMutableProjection(streamedKeys, streamedPlan.output)()
    }

  protected def hashJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation): Iterator[InternalRow] =
  {
    new Iterator[InternalRow] {
      private[this] var currentStreamedRow: InternalRow = _
      private[this] var currentHashMatches: CompactBuffer[InternalRow] = _
      private[this] var currentMatchPosition: Int = -1

      // Mutable per row objects.
      private[this] val joinRow = new JoinedRow2
      private[this] val resultProjection: Projection = {
        if (supportUnsafe) {
          UnsafeProjection.create(self.schema)
        } else {
          new Projection {
            override def apply(r: InternalRow): InternalRow = r
          }
        }
      }

      private[this] val joinKeys = streamSideKeyGenerator

      override final def hasNext: Boolean =
        (currentMatchPosition != -1 && currentMatchPosition < currentHashMatches.size) ||
          (streamIter.hasNext && fetchNext())

      override final def next(): InternalRow = {
        val ret = buildSide match {
          case BuildRight => joinRow(currentStreamedRow, currentHashMatches(currentMatchPosition))
          case BuildLeft => joinRow(currentHashMatches(currentMatchPosition), currentStreamedRow)
        }
        currentMatchPosition += 1
        resultProjection(ret)
      }

      /**
       * Searches the streamed iterator for the next row that has at least one match in hashtable.
       *
       * @return true if the search is successful, and false if the streamed iterator runs out of
       *         tuples.
       */
      private final def fetchNext(): Boolean = {
        currentHashMatches = null
        currentMatchPosition = -1

        while (currentHashMatches == null && streamIter.hasNext) {
          currentStreamedRow = streamIter.next()
          val key = joinKeys(currentStreamedRow)
          if (!key.anyNull) {
            currentHashMatches = hashedRelation.get(key)
          }
        }

        if (currentHashMatches == null) {
          false
        } else {
          currentMatchPosition = 0
          true
        }
      }
    }
  }

  protected[this] def buildHashRelation(buildIter: Iterator[InternalRow]): HashedRelation = {
    if (supportUnsafe) {
      UnsafeHashedRelation(buildIter, buildKeys, buildPlan)
    } else {
      HashedRelation(buildIter, newProjection(buildKeys, buildPlan.output))
    }
  }
}