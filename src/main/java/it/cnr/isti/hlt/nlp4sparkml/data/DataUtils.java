/*
 *
 * ****************
 * Copyright 2015 Tiziano Fagni (tiziano.fagni@isti.cnr.it)
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
 * ******************
 */

package it.cnr.isti.hlt.nlp4sparkml.data;


import it.cnr.isti.hlt.nlp4sparkml.utils.Cond;
import org.apache.commons.lang.ArrayUtils;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.mllib.linalg.SparseVector;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.*;
import scala.Tuple2;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author Tiziano Fagni (tiziano.fagni@isti.cnr.it)
 */
public class DataUtils {

    public static final String POINT_ID = "pointID";
    public static final String FEATURES = "labels";
    public static final String WEIGHTS = "weights";
    public static final String LABELS = "labels";
    public static final String SCORES = "scores";
    public static final String POSITIVE_THRESHOLDS = "positiveThresholds";

    /**
     * Load data file in LibSVm format. The documents IDs are assigned according to the row index in the original
     * file, i.e. useful at classification time. We are assuming that the feature IDs are the same as the training
     * file used to build the classification model.
     *
     * @param sc       The spark context.
     * @param dataFile The data file.
     * @return An RDD containing the read points.
     */
    public static JavaRDD<MultilabelPoint> loadLibSvmFileFormatDataAsList(JavaSparkContext sc, String dataFile, boolean labels0Based, boolean binaryProblem) {
        if (sc == null)
            throw new NullPointerException("The Spark Context is 'null'");
        if (dataFile == null || dataFile.isEmpty())
            throw new IllegalArgumentException("The dataFile is 'null'");

        JavaRDD<String> lines = sc.textFile(dataFile).cache();
        int numFeatures = computeNumFeaturesFromLibSvmFormat(lines);

        ArrayList<MultilabelPoint> points = new ArrayList<>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(dataFile));

            try {
                int docID = 0;
                String line = br.readLine();
                while (line != null) {
                    if (line.isEmpty())
                        return null;
                    String[] fields = line.split("\\s+");
                    String[] t = fields[0].split(",");
                    int[] labels = new int[0];
                    if (!binaryProblem) {
                        labels = new int[t.length];
                        for (int i = 0; i < t.length; i++) {
                            String label = t[i];
                            if (labels0Based)
                                labels[i] = new Double(Double.parseDouble(label)).intValue();
                            else
                                labels[i] = new Double(Double.parseDouble(label)).intValue() - 1;
                            if (labels[i] < 0)
                                throw new IllegalArgumentException("In current configuration I obtain a negative label ID value. Please check if this is a problem binary or multiclass " +
                                        "and if the labels IDs are in form 0-based or 1-based");
                        }
                    } else {
                        if (t.length > 1)
                            throw new IllegalArgumentException("In binary problem you can only specify one label ID (+1 or -1) per document as valid label IDs");
                        int label = new Double(Double.parseDouble(t[0])).intValue();
                        if (label > 0) {
                            labels = new int[]{0};
                        }
                    }
                    ArrayList<Integer> indexes = new ArrayList<Integer>();
                    ArrayList<Double> values = new ArrayList<Double>();
                    for (int j = 1; j < fields.length; j++) {
                        String data = fields[j];
                        if (data.startsWith("#"))
                            // Beginning of a comment. Skip it.
                            break;
                        String[] featInfo = data.split(":");
                        // Transform feature ID value in 0-based.
                        int featID = Integer.parseInt(featInfo[0]) - 1;
                        double value = Double.parseDouble(featInfo[1]);
                        indexes.add(featID);
                        values.add(value);
                    }

                    points.add(new MultilabelPoint(docID, numFeatures, indexes.stream().mapToInt(i -> i).toArray(), values.stream().mapToDouble(i -> i).toArray(), labels));

                    line = br.readLine();
                    docID++;
                }
            } finally {
                br.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("Reading input LibSVM data file", e);
        }

        return sc.parallelize(points);
    }

    /**
     * Load data file in LibSVm format. The documents IDs are assigned arbitrarily by Spark.
     *
     * @param sc       The spark context.
     * @param dataFile The data file.
     * @return An RDD containing the read points.
     */
    public static JavaRDD<MultilabelPoint> loadLibSvmFileFormatData(JavaSparkContext sc, String dataFile, boolean labels0Based, boolean binaryProblem) {
        if (sc == null)
            throw new NullPointerException("The Spark Context is 'null'");
        if (dataFile == null || dataFile.isEmpty())
            throw new IllegalArgumentException("The dataFile is 'null'");
        JavaRDD<String> lines = sc.textFile(dataFile).cache();
        int localNumFeatures = computeNumFeaturesFromLibSvmFormat(lines);
        Broadcast<Integer> distNumFeatures = sc.broadcast(localNumFeatures);
        JavaRDD<MultilabelPoint> docs = lines.filter(line -> !line.isEmpty()).zipWithIndex().map(item -> {
            int numFeatures = distNumFeatures.getValue();
            String line = item._1();
            long indexLong = item._2();
            int index = (int) indexLong;
            String[] fields = line.split("\\s+");
            String[] t = fields[0].split(",");
            int[] labels = new int[0];
            if (!binaryProblem) {
                labels = new int[t.length];
                for (int i = 0; i < t.length; i++) {
                    String label = t[i];
                    // Labels should be already 0-based.
                    if (labels0Based)
                        labels[i] = new Double(Double.parseDouble(label)).intValue();
                    else
                        labels[i] = new Double(Double.parseDouble(label)).intValue() - 1;
                    if (labels[i] < 0)
                        throw new IllegalArgumentException("In current configuration I obtain a negative label ID value. Please check if this is a problem binary or multiclass " +
                                "and if the labels IDs are in form 0-based or 1-based");
                    assert (labels[i] >= 0);
                }
            } else {
                if (t.length > 1)
                    throw new IllegalArgumentException("In binary problem you can only specify one label ID (+1 or -1) per document as valid label IDs");
                int label = new Double(Double.parseDouble(t[0])).intValue();
                if (label > 0) {
                    labels = new int[]{0};
                }
            }
            ArrayList<Integer> indexes = new ArrayList<Integer>();
            ArrayList<Double> values = new ArrayList<Double>();
            for (int j = 1; j < fields.length; j++) {
                String data = fields[j];
                if (data.startsWith("#"))
                    // Beginning of a comment. Skip it.
                    break;
                String[] featInfo = data.split(":");
                // Transform feature ID value in 0-based.
                int featID = Integer.parseInt(featInfo[0]) - 1;
                double value = Double.parseDouble(featInfo[1]);
                indexes.add(featID);
                values.add(value);
            }

            return new MultilabelPoint(index, numFeatures, indexes.stream().mapToInt(i -> i).toArray(), values.stream().mapToDouble(i -> i).toArray(), labels);
        });

        return docs;
    }

    public static int computeNumFeaturesFromLibSvmFormat(JavaRDD<String> lines) {
        int maxFeatureID = lines.map(line -> {
            if (line.isEmpty())
                return -1;
            String[] fields = line.split("\\s+");
            int maximumFeatID = 0;
            for (int j = 1; j < fields.length; j++) {
                String data = fields[j];
                if (data.startsWith("#"))
                    // Beginning of a comment. Skip it.
                    break;
                String[] featInfo = data.split(":");
                int featID = Integer.parseInt(featInfo[0]);
                maximumFeatID = Math.max(featID, maximumFeatID);
            }
            return maximumFeatID;
        }).reduce((val1, val2) -> val1 < val2 ? val2 : val1);

        return maxFeatureID;
    }


    public static int computeNumFeaturesFromDataFrame(JavaRDD<Row> rows, String fieldName) {
        int maxFeatureID = rows.map(row -> {
            Row rowFeatures = row.getStruct(row.fieldIndex(fieldName));
            List<Integer> features = rowFeatures.getList(rowFeatures.fieldIndex(FEATURES));
            int maximumFeatID = 0;
            for (int featureID : features) {
                if (featureID > maximumFeatID)
                    maximumFeatID = featureID;
            }
            return maximumFeatID;
        }).reduce((val1, val2) -> val1 < val2 ? val2 : val1);

        return maxFeatureID;
    }


    public static int getNumDocuments(JavaRDD<MultilabelPoint> documents) {
        if (documents == null)
            throw new NullPointerException("The documents RDD is 'null'");
        return (int) documents.count();
    }

    public static int getNumLabels(JavaRDD<MultilabelPoint> documents) {
        if (documents == null)
            throw new NullPointerException("The documents RDD is 'null'");
        int maxValidLabelID = documents.map(doc -> {
            List<Integer> values = Arrays.asList(ArrayUtils.toObject(doc.getLabels()));
            if (values.size() == 0)
                return 0;
            else
                return Collections.max(values);
        }).reduce((m1, m2) -> Math.max(m1, m2));
        return maxValidLabelID + 1;
    }

    public static int getNumFeatures(JavaRDD<MultilabelPoint> documents) {
        if (documents == null)
            throw new NullPointerException("The documents RDD is 'null'");
        return documents.take(1).get(0).getNumFeatures();
    }

    public static JavaRDD<LabelDocuments> getLabelDocuments(JavaRDD<MultilabelPoint> documents) {
        return documents.flatMapToPair(doc -> {
            int[] labels = doc.getLabels();
            ArrayList<Integer> docAr = new ArrayList<Integer>();
            docAr.add(doc.getPointID());
            ArrayList<Tuple2<Integer, ArrayList<Integer>>> ret = new ArrayList<Tuple2<Integer, ArrayList<Integer>>>();
            for (int i = 0; i < labels.length; i++) {
                ret.add(new Tuple2<>(labels[i], docAr));
            }
            return ret;
        }).reduceByKey((list1, list2) -> {
            ArrayList<Integer> ret = new ArrayList<Integer>();
            ret.addAll(list1);
            ret.addAll(list2);
            Collections.sort(ret);
            return ret;
        }).map(item -> {
            return new LabelDocuments(item._1(), item._2().stream().mapToInt(i -> i).toArray());
        });
    }

    public static JavaRDD<FeatureDocuments> getFeatureDocuments(JavaRDD<MultilabelPoint> documents) {
        return documents.flatMapToPair(doc -> {
            SparseVector feats = doc.getFeaturesAsSparseVector();
            int[] indices = feats.indices();
            ArrayList<Tuple2<Integer, FeatureDocuments>> ret = new ArrayList<>();
            for (int i = 0; i < indices.length; i++) {
                int featureID = indices[i];
                int[] docs = new int[]{doc.getPointID()};
                int[][] labels = new int[1][];
                labels[0] = doc.getLabels();
                ret.add(new Tuple2<>(featureID, new FeatureDocuments(featureID, docs, labels)));
            }
            return ret;
        }).reduceByKey((f1, f2) -> {
            int numDocs = f1.getDocuments().length + f2.getDocuments().length;
            int[] docsMerged = new int[numDocs];
            int[][] labelsMerged = new int[numDocs][];
            // Add first feature info.
            for (int idx = 0; idx < f1.getDocuments().length; idx++) {
                docsMerged[idx] = f1.getDocuments()[idx];
            }
            for (int idx = 0; idx < f1.getDocuments().length; idx++) {
                labelsMerged[idx] = f1.getLabels()[idx];
            }

            // Add second feature info.
            for (int idx = f1.getDocuments().length; idx < numDocs; idx++) {
                docsMerged[idx] = f2.getDocuments()[idx - f1.getDocuments().length];
            }
            for (int idx = f1.getDocuments().length; idx < numDocs; idx++) {
                labelsMerged[idx] = f2.getLabels()[idx - f1.getDocuments().length];
            }
            return new FeatureDocuments(f1.featureID, docsMerged, labelsMerged);
        }).map(item -> item._2());
    }


    public static DataType multilabelPointDataType() {
        List<StructField> fields = new ArrayList<>();
        fields.add(DataTypes.createStructField(POINT_ID, DataTypes.IntegerType, false));
        fields.add(DataTypes.createStructField(FEATURES, DataTypes.createArrayType(DataTypes.IntegerType, false), false));
        fields.add(DataTypes.createStructField(WEIGHTS, DataTypes.createArrayType(DataTypes.DoubleType, false), false));
        fields.add(DataTypes.createStructField(LABELS, DataTypes.createArrayType(DataTypes.IntegerType, false), false));
        StructType st = DataTypes.createStructType(fields);
        return st;
    }

    public static MultilabelPoint toMultilabelPoint(Row row, String fieldName, int numFeatures) {
        Cond.requireNotNull(row, "row");
        Cond.requireNotNull(fieldName, "fieldName");
        Cond.require(!fieldName.isEmpty(), "The field name is empty");
        int idx = row.fieldIndex(fieldName);
        Cond.require(idx >= 0, "The requested field name <" + fieldName + "> is not available");
        Row inputPoint = row.getStruct(idx);

        int pointID = inputPoint.getInt(inputPoint.fieldIndex(POINT_ID));
        int[] features = toIntArray(inputPoint.getList(inputPoint.fieldIndex(FEATURES)));
        double[] weights = toDoubleArray(inputPoint.getList(inputPoint.fieldIndex(WEIGHTS)));
        int[] labels = toIntArray(inputPoint.getList(inputPoint.fieldIndex(LABELS)));
        MultilabelPoint point = new MultilabelPoint(pointID, numFeatures, features, weights, labels);
        return point;
    }

    public static DataType pointClassificationResultsDataType() {
        List<StructField> fields = new ArrayList<>();
        fields.add(DataTypes.createStructField(POINT_ID, DataTypes.IntegerType, false));
        fields.add(DataTypes.createStructField(LABELS, DataTypes.createArrayType(DataTypes.IntegerType, false), false));
        fields.add(DataTypes.createStructField(SCORES, DataTypes.createArrayType(DataTypes.DoubleType, false), false));
        fields.add(DataTypes.createStructField(POSITIVE_THRESHOLDS, DataTypes.createArrayType(DataTypes.DoubleType, false), false));
        StructType st = DataTypes.createStructType(fields);
        return st;
    }

    public static void checkMultilabelPointDataType(StructType dt) {
        Cond.require(dt.fieldIndex(POINT_ID) >= 0, "The field " + POINT_ID + " does not exist!");
        Cond.require(dt.fieldIndex(LABELS) >= 0, "The field " + LABELS + " does not exist!");
        Cond.require(dt.fieldIndex(SCORES) >= 0, "The field " + SCORES + " does not exist!");
        Cond.require(dt.fieldIndex(POSITIVE_THRESHOLDS) >= 0, "The field " + POSITIVE_THRESHOLDS + " does not exist!");
    }


    public static PointClassificationResults toPointClassificationResults(Row row, String fieldName) {
        Cond.requireNotNull(row, "row");
        Cond.requireNotNull(fieldName, "fieldName");
        Cond.require(!fieldName.isEmpty(), "fieldName is empty");
        int idx = row.fieldIndex(fieldName);
        Cond.require(idx >= 0, "The requested field name <" + fieldName + "> is not available");
        Row clResults = row.getStruct(idx);
        int pointID = clResults.getInt(clResults.fieldIndex(DataUtils.POINT_ID));
        int[] labels = DataUtils.toIntArray(clResults.getList(clResults.fieldIndex(DataUtils.LABELS)));
        double[] scores = DataUtils.toDoubleArray(clResults.getList(clResults.fieldIndex(DataUtils.SCORES)));
        double[] positiveThreshold = DataUtils.toDoubleArray(clResults.getList(clResults.fieldIndex(DataUtils.POSITIVE_THRESHOLDS)));
        return new PointClassificationResults(row, fieldName);
    }


    public static int[] toIntArray(List<Integer> l) {
        Cond.requireNotNull(l, "l");
        int[] ret = new int[l.size()];
        for (int i = 0; i < l.size(); i++)
            ret[i] = l.get(i);
        return ret;
    }


    public static double[] toDoubleArray(List<Double> l) {
        Cond.requireNotNull(l, "l");
        double[] ret = new double[l.size()];
        for (int i = 0; i < l.size(); i++)
            ret[i] = l.get(i);
        return ret;
    }

    public static float[] toFloatArray(List<Float> l) {
        Cond.requireNotNull(l, "l");
        float[] ret = new float[l.size()];
        for (int i = 0; i < l.size(); i++)
            ret[i] = l.get(i);
        return ret;
    }


    public static class LabelDocuments implements Serializable {
        private final int labelID;
        private final int[] documents;

        public LabelDocuments(int labelID, int[] documents) {
            this.labelID = labelID;
            this.documents = documents;
        }

        public int getLabelID() {
            return labelID;
        }

        public int[] getDocuments() {
            return documents;
        }
    }

    public static class FeatureDocuments implements Serializable {
        private final int featureID;
        private final int[] documents;
        private final int[][] labels;

        public FeatureDocuments(int featureID, int[] documents, int[][] labels) {
            this.featureID = featureID;
            this.documents = documents;
            this.labels = labels;
        }

        public int getFeatureID() {
            return featureID;
        }

        public int[] getDocuments() {
            return documents;
        }

        public int[][] getLabels() {
            return labels;
        }
    }
}
