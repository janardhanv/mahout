/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.clustering.dirichlet;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.apache.hadoop.conf.Configuration;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.apache.mahout.clustering.Cluster;
import org.apache.mahout.clustering.Model;
import org.apache.mahout.clustering.ModelDistribution;
import org.apache.mahout.clustering.classify.ClusterClassifier;
import org.apache.mahout.clustering.dirichlet.models.DistanceMeasureClusterDistribution;
import org.apache.mahout.clustering.dirichlet.models.DistributionDescription;
import org.apache.mahout.clustering.dirichlet.models.GaussianClusterDistribution;
import org.apache.mahout.clustering.iterator.ClusterIterator;
import org.apache.mahout.clustering.iterator.DirichletClusteringPolicy;
import org.apache.mahout.common.distance.CosineDistanceMeasure;
import org.apache.mahout.common.distance.ManhattanDistanceMeasure;
import org.apache.mahout.math.RandomAccessSparseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.apache.mahout.utils.MahoutTestCase;
import org.apache.mahout.utils.vectors.TermInfo;
import org.apache.mahout.utils.vectors.lucene.CachedTermInfo;
import org.apache.mahout.utils.vectors.lucene.LuceneIterable;
import org.apache.mahout.utils.vectors.lucene.TFDFMapper;
import org.apache.mahout.utils.vectors.lucene.VectorMapper;
import org.apache.mahout.vectorizer.TFIDF;
import org.apache.mahout.vectorizer.Weight;
import org.junit.Test;

import com.google.common.collect.Lists;
import com.google.common.io.Closeables;

public final class TestL1ModelClustering extends MahoutTestCase {
  
  private class MapElement implements Comparable<MapElement> {
    
    private final Double pdf;
    private final String doc;
    
    MapElement(double pdf, String doc) {
      this.pdf = pdf;
      this.doc = doc;
    }
    
    @Override
    // reverse compare to sort in reverse order
    public int compareTo(MapElement e) {
      if (e.pdf > pdf) {
        return 1;
      } else if (e.pdf < pdf) {
        return -1;
      } else {
        return 0;
      }
    }
    
    @Override
    public int hashCode() {
      int hash = pdf == null ? 0 : pdf.hashCode();
      hash ^= doc == null ? 0 : doc.hashCode();
      return hash;
    }
    
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof MapElement)) {
        return false;
      }
      MapElement otherElement = (MapElement) other;
      if ((pdf == null && otherElement.pdf != null) || (pdf != null && !pdf.equals(otherElement.pdf))) {
        return false;
      }
      return doc == null ? otherElement.doc == null : doc.equals(otherElement.doc);
    }
    
    @Override
    public String toString() {
      return pdf.toString() + ' ' + doc;
    }
    
  }
  
  private static final String[] DOCS = {"The quick red fox jumped over the lazy brown dogs.",
      "The quick brown fox jumped over the lazy red dogs.", "The quick red cat jumped over the lazy brown dogs.",
      "The quick brown cat jumped over the lazy red dogs.", "Mary had a little lamb whose fleece was white as snow.",
      "Moby Dick is a story of a whale and a man obsessed.",
      "The robber wore a black fleece jacket and a baseball cap.",
      "The English Springer Spaniel is the best of all dogs."};
  
  private static final String[] DOCS2 = {"The quick red fox jumped over the lazy brown dogs.",
      "The quick brown fox jumped over the lazy red dogs.", "The quick red cat jumped over the lazy brown dogs.",
      "The quick brown cat jumped over the lazy red dogs.", "Mary had a little lamb whose fleece was white as snow.",
      "Mary had a little goat whose fleece was white as snow.",
      "Mary had a little lamb whose fleece was black as tar.",
      "Dick had a little goat whose fleece was white as snow.", "Moby Dick is a story of a whale and a man obsessed.",
      "Moby Bob is a story of a walrus and a man obsessed.", "Moby Dick is a story of a whale and a crazy man.",
      "The robber wore a black fleece jacket and a baseball cap.",
      "The robber wore a red fleece jacket and a baseball cap.",
      "The robber wore a white fleece jacket and a baseball cap.",
      "The English Springer Spaniel is the best of all dogs."};
  
  private List<Vector> sampleData;
  
  private void getSampleData(String[] docs2) throws IOException {
    sampleData = Lists.newArrayList();
    RAMDirectory directory = new RAMDirectory();
    IndexWriter writer = new IndexWriter(directory, new StandardAnalyzer(Version.LUCENE_34), true,
        IndexWriter.MaxFieldLength.UNLIMITED);
    try {
      for (int i = 0; i < docs2.length; i++) {
        Document doc = new Document();
        Fieldable id = new Field("id", "doc_" + i, Field.Store.YES, Field.Index.NOT_ANALYZED_NO_NORMS);
        doc.add(id);
        // Store both position and offset information
        Fieldable text = new Field("content", docs2[i], Field.Store.NO, Field.Index.ANALYZED, Field.TermVector.YES);
        doc.add(text);
        writer.addDocument(doc);
      }
    } finally {
      Closeables.closeQuietly(writer);
    }
    IndexReader reader = IndexReader.open(directory, true);
    Weight weight = new TFIDF();
    TermInfo termInfo = new CachedTermInfo(reader, "content", 1, 100);
    VectorMapper mapper = new TFDFMapper(reader, weight, termInfo);
    Iterable<Vector> iterable = new LuceneIterable(reader, "id", "content", mapper);
    
    int i = 0;
    for (Vector vector : iterable) {
      assertNotNull(vector);
      System.out.println("Vector[" + i++ + "]=" + formatVector(vector));
      sampleData.add(vector);
    }
  }
  
  private String formatVector(Vector v) {
    StringBuilder buf = new StringBuilder();
    int nzero = 0;
    Iterator<Vector.Element> iterateNonZero = v.iterateNonZero();
    while (iterateNonZero.hasNext()) {
      iterateNonZero.next();
      nzero++;
    }
    buf.append('(').append(nzero);
    buf.append("nz) [");
    // handle sparse Vectors gracefully, suppressing zero values
    int nextIx = 0;
    for (int i = 0; i < v.size(); i++) {
      double elem = v.get(i);
      if (elem == 0.0) {
        continue;
      }
      if (i > nextIx) {
        buf.append("..{").append(i).append("}=");
      }
      buf.append(String.format(Locale.ENGLISH, "%.2f", elem)).append(", ");
      nextIx = i + 1;
    }
    buf.append(']');
    return buf.toString();
  }
  
  private void printSamples(Iterable<Cluster[]> result, int significant) {
    int row = 0;
    for (Cluster[] r : result) {
      int sig = 0;
      for (Cluster model : r) {
        if (model.getNumObservations() > significant) {
          sig++;
        }
      }
      System.out.print("sample[" + row++ + "] (" + sig + ")= ");
      for (Cluster model : r) {
        if (model.getNumObservations() > significant) {
          System.out.print(model.asFormatString(null) + ", ");
        }
      }
      System.out.println();
    }
    System.out.println();
  }
  
  private void printClusters(List<Cluster> models, String[] docs) {
    for (int m = 0; m < models.size(); m++) {
      Cluster model = models.get(m);
      long count = model.getNumObservations();
      if (count == 0) {
        continue;
      }
      System.out.println("Model[" + m + "] had " + count + " hits (!) and " + (sampleData.size() - count)
          + " misses (? in pdf order) during the last iteration:");
      MapElement[] map = new MapElement[sampleData.size()];
      // sort the samples by pdf
      for (int i = 0; i < sampleData.size(); i++) {
        VectorWritable sample = new VectorWritable(sampleData.get(i));
        map[i] = new MapElement(model.pdf(sample), docs[i]);
      }
      Arrays.sort(map);
      // now find the n=model.count() most likely docs and output them
      for (int i = 0; i < map.length; i++) {
        if (i < count) {
          System.out.print("! ");
        } else {
          System.out.print("? ");
        }
        System.out.println(map[i].doc);
      }
    }
  }
  
  @Test
  public void testDocs() throws Exception {
    getSampleData(DOCS);
    DistributionDescription description = new DistributionDescription(GaussianClusterDistribution.class.getName(),
        RandomAccessSparseVector.class.getName(), ManhattanDistanceMeasure.class.getName(), sampleData.get(0).size());
    
    List<Cluster> models = Lists.newArrayList();
    ModelDistribution<VectorWritable> modelDist = description.createModelDistribution(new Configuration());
    for (Model<VectorWritable> cluster : modelDist.sampleFromPrior(15)) {
      models.add((Cluster) cluster);
    }
    
    ClusterIterator iterator = new ClusterIterator();
    ClusterClassifier classifier = new ClusterClassifier(models, new DirichletClusteringPolicy(15, 1.0));
    ClusterClassifier posterior = iterator.iterate(sampleData, classifier, 10);
    
    printClusters(posterior.getModels(), DOCS);
  }
  
  @Test
  public void testDMDocs() throws Exception {
    
    getSampleData(DOCS);
    DistributionDescription description = new DistributionDescription(
        DistanceMeasureClusterDistribution.class.getName(), RandomAccessSparseVector.class.getName(),
        CosineDistanceMeasure.class.getName(), sampleData.get(0).size());
    
    List<Cluster> models = Lists.newArrayList();
    ModelDistribution<VectorWritable> modelDist = description.createModelDistribution(new Configuration());
    for (Model<VectorWritable> cluster : modelDist.sampleFromPrior(15)) {
      models.add((Cluster) cluster);
    }
    
    ClusterIterator iterator = new ClusterIterator();
    ClusterClassifier classifier = new ClusterClassifier(models, new DirichletClusteringPolicy(15, 1.0));
    ClusterClassifier posterior = iterator.iterate(sampleData, classifier, 10);
    
    printClusters(posterior.getModels(), DOCS);
  }
  
  @Test
  public void testDocs2() throws Exception {
    getSampleData(DOCS2);
    DistributionDescription description = new DistributionDescription(GaussianClusterDistribution.class.getName(),
        RandomAccessSparseVector.class.getName(), ManhattanDistanceMeasure.class.getName(), sampleData.get(0).size());
    
    List<Cluster> models = Lists.newArrayList();
    ModelDistribution<VectorWritable> modelDist = description.createModelDistribution(new Configuration());
    for (Model<VectorWritable> cluster : modelDist.sampleFromPrior(15)) {
      models.add((Cluster) cluster);
    }
    
    ClusterIterator iterator = new ClusterIterator();
    ClusterClassifier classifier = new ClusterClassifier(models, new DirichletClusteringPolicy(15, 1.0));
    ClusterClassifier posterior = iterator.iterate(sampleData, classifier, 10);
    
    printClusters(posterior.getModels(), DOCS2);
  }
  
  @Test
  public void testDMDocs2() throws Exception {
    
    getSampleData(DOCS);
    DistributionDescription description = new DistributionDescription(
        DistanceMeasureClusterDistribution.class.getName(), RandomAccessSparseVector.class.getName(),
        CosineDistanceMeasure.class.getName(), sampleData.get(0).size());
    
    List<Cluster> models = Lists.newArrayList();
    ModelDistribution<VectorWritable> modelDist = description.createModelDistribution(new Configuration());
    for (Model<VectorWritable> cluster : modelDist.sampleFromPrior(15)) {
      models.add((Cluster) cluster);
    }
    
    ClusterIterator iterator = new ClusterIterator();
    ClusterClassifier classifier = new ClusterClassifier(models, new DirichletClusteringPolicy(15, 1.0));
    ClusterClassifier posterior = iterator.iterate(sampleData, classifier, 10);
    
    printClusters(posterior.getModels(), DOCS2);
  }
  
}
