package LearningShapeletOrders;

import DataStructures.DataSet;
import DataStructures.Matrix;
import org.happy.commons.concurrent.loops.ForEachTask_1x0;
import org.happy.commons.concurrent.loops.Parallel_1x0;
import utilities.InstanceTools;
import utilities.Logging;
import utilities.StatisticalUtilities;
import weka.clusterers.SimpleKMeans;
import weka.core.Instances;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import static utilities.StatisticalUtilities.calculateSigmoid;

public class LearnShapeletOrdersBaselineBinaryClassification {
    //public MultivariateDataset dataSet;
    public String trainSetPath, testSetPath;
    public boolean normalizeData;
    public double paaRatio;
    //public double[][] train2D;
    //public double[][] test2D;

    public int ITrain, ITest; // number of training and testing instances
    // length of a time-series
    public int Q;
    // length of shapelet
    public int L[];
    public int L_min;
    // number of latent patterns
    public int K;
    //maximum number of orders
    public int H;
    // scales of the shapelet length
    public int R;
    // number of classes
    public int C;
    // number of segments
    public int J[];
    // time series data and the label
    public Matrix T;
    public Matrix Y; //Y_b;

    // shapelets
    double Shapelets[][][];
    // the softmax parameter
    public double alpha;
    // accumulate the gradients
    double GradHistShapelets[][][];
    //double GradHistW_k[][][];
    //double GradHistW_h[][][];
    double GradHistW[][];
    //double GradHist_u[];
    double GradHistBiasW;

    public int maxIter;
    // the learning rate
    public double eta;
    public int kMeansIter;
    // the regularization parameters
    //public double lambdaW_k;
    //public double lambdaW_h;
    public double lambdaW;
    //public double delta[];
    //public double u[];
    public List<Double> nominalLabels;

    // structures for storing the precomputed terms
    double D[][][][];
    double E[][][][];
    //double E_alt[][][][];
    double startTime[][][][];
    double M[][][];
    double B[][][];
    double G[][][];
    double A[][][];
    double Psi[][][];
    double sigY[];


    Random rand = new Random();

    List<Integer> instanceIdxs;
    List<Integer> rIdxs;

    // classification weights
    //double W_k[][][];
    //double W_h[][][];
    double W[][];
    double biasW;

    //public List<Double> nominalLabels;


//    public void CreateOneVsAllTargets() {
//        C = nominalLabels.size();
//
//        Y_b = new Matrix(ITrain + ITest, C);
//
//        // initialize the extended representation
//        for (int i = 0; i < ITrain + ITest; i++) {
//            // firts set everything to zero
//            for (int c = 0; c < C; c++)
//                Y_b.set(i, c, 0);
//
//            // then set the real label index to 1
//            int indexLabel = nominalLabels.indexOf(Y.get(i, 0));
//            Y_b.set(i, indexLabel, 1.0);
//        }
//
//    }

    public void Initialize() {
        // avoid K=0
        if (K == 0)
            K = 1;

        // set the labels to be binary 0 and 1, needed for the logistic loss
        // CreateOneVsAllTargets();
        for (int i = 0; i < ITrain + ITest; i++)
            if (Y.get(i) != 1.0)
                Y.set(i, 0, 0.0);
        C = nominalLabels.size();

        double positive = 0;
        double negative = 0;
        for (int i = 0; i < ITrain; i++) {
            if (Y.get(i) == 1)
                positive = positive + 1;
            if (Y.get(i) == 0)
                negative = negative + 1;
        }
        //System.out.println("positive = " + positive);
        //System.out.println("negative = " + negative);

        C = nominalLabels.size();


        // initialize the shapelets (complete initialization during the clustering)
        Shapelets = new double[R][][];
        GradHistShapelets = new double[R][][];

        // initialize the number of shapelets and the length of the shapelets
        J = new int[R];
        L = new int[R];
        // set the lengths of shapelets and the number of segments
        // at each scale r
        int totalSegments = 0;
        for (int r = 0; r < R; r++) {
            L[r] = (r + 1) * L_min;
            J[r] = Q - L[r];

            totalSegments += ITrain * J[r];
        }

        // set the total number of shapelets per scale as a rule of thumb
        // to the logarithm of the total segments
        if (K < 0)
            K = (int) Math.log(totalSegments) * (C - 1);
        //System.out.println("K: " + K);

        H = K * (K - 1) / 2;//total Number of orders
        //System.out.println("Shapeletspace size: " + K +" " + "Order space size: " + H);
        //System.out.println(K);
        //Logging.println("Original LTS");
        //Logging.println("ITrain="+ITrain + ", ITest="+ITest + ", Q="+Q + ", Classes="+C, LogLevel.DEBUGGING_LOG);
        //Logging.println("K="+K + ", L_min="+ L_min + ", R="+R, LogLevel.DEBUGGING_LOG);
        //Logging.println("eta=" + eta + ", maxIter="+ maxIter, LogLevel.DEBUGGING_LOG);
        //Logging.println("lambdaW="+lambdaW + ", alpha="+ alpha, LogLevel.DEBUGGING_LOG);
        //Logging.println("totalSegments="+totalSegments + ", K="+ K, LogLevel.DEBUGGING_LOG);

        // initialize an array of the sizes
        rIdxs = new ArrayList<Integer>();
        for (int r = 0; r < R; r++)
            rIdxs.add(r);

        // initialize shapelets
        InitializeShapeletsKMeans();


        // initialize the terms for pre-computation
        D = new double[ITrain + ITest][R][K][];
        E = new double[ITrain + ITest][R][K][];
        startTime = new double[ITrain + ITest][R][K][];

        for (int i = 0; i < ITrain + ITest; i++)
            for (int r = 0; r < R; r++)
                for (int k = 0; k < K; k++) {
                    D[i][r][k] = new double[J[r]];
                    E[i][r][k] = new double[J[r]];
                    startTime[i][r][k] = new double[J[r]];
                }

        // initialize the placeholders for the precomputed values
        M = new double[ITrain + ITest][R][K];
        G = new double[ITrain + ITest][R][H];
        A = new double[ITrain + ITest][R][K + H];
        B = new double[ITrain + ITest][R][K];
        Psi = new double[ITrain + ITest][R][K];
        sigY = new double[ITrain + ITest];

        // initialize the weights

        //W_k = new double[C][R][K];
        //W_h = new double[C][R][H];
        W = new double[R][K + H];
        // biasW = new double;
        //delta = new double[C];
        //u = new double[C];
        //for (int c = 0; c<C;c++)

        GradHistW = new double[R][K + H];
        //GradHistBiasW = new double[C];
        //GradHistW_k = new double[C][R][K];

        //GradHistW_h = new double[C][R][H];
        //GradHist_u = new double[C];


        //for (int c = 0; c < C; c++) {
        for (int r = 0; r < R; r++) {
            for (int kh = 0; kh < K + H; kh++) {
                W[r][kh] = 2 * rand.nextDouble() - 1;
                GradHistW[r][kh] = 0;
            }

//                for(int h = 0; h < H; h++){
//                    W_h[c][r][h] = 2*rand.nextDouble()-1;
//                    GradHistW_h[c][r][h] = 0;
//                }

            for (int kh = 0; kh < K + H; kh++) {
                W[r][kh] = 2 * rand.nextDouble() - 1;
                GradHistW[r][kh] = 0;

            }


            biasW = 2 * rand.nextDouble() - 1;
            GradHistBiasW = 0;
            //delta[c] = 0.5; // Random number between 0 and 1
            //u[c] = Math.log(delta[c]);
        }
        //}

        // precompute the M, Psi, sigY, used later for setting initial W
        for (int i = 0; i < ITrain + ITest; i++)
            PreCompute(i);

        // store all the instances indexes for
        instanceIdxs = new ArrayList<Integer>();
        for (int i = 0; i < ITrain; i++)
            instanceIdxs.add(i);

        // shuffle the order for a better convergence
        Collections.shuffle(instanceIdxs, rand);

        //Logging.println("Initializations Completed!", LogLevel.DEBUGGING_LOG);
    }

    public void PreCompute(int i) {
        // precompute terms
        for (int r = 0; r < R; r++) {
            for (int k = 0; k < K; k++) {
                for (int j = 0; j < J[r]; j++) {
                    // precompute D
                    D[i][r][k][j] = 0;
                    double err = 0;

                    for (int l = 0; l < L[r]; l++) {
                        err = T.get(i, j + l) - Shapelets[r][k][l];
                        //err = T.get(i, j+l)-getMean- Shapelets[r][k][l];
                        //System.out.print(Shapelets[r][k][l] + " ");
                        D[i][r][k][j] += err * err;
                        startTime[i][r][k][j] = j + l;
                    }

                    D[i][r][k][j] /= (double) L[r];
                    // precompute E
                    E[i][r][k][j] = Math.exp(alpha * D[i][r][k][j]);

                }

                // precompute Psi
                Psi[i][r][k] = 0;
                for (int j = 0; j < J[r]; j++)
                    Psi[i][r][k] += Math.exp(alpha * D[i][r][k][j]);

                // precompute M
                M[i][r][k] = 0;
                B[i][r][k] = 0;

                for (int j = 0; j < J[r]; j++) {
                    M[i][r][k] += D[i][r][k][j] * E[i][r][k][j];
                    B[i][r][k] += startTime[i][r][k][j] * E[i][r][k][j];
                }

                M[i][r][k] /= Psi[i][r][k];
                B[i][r][k] /= Psi[i][r][k];
                B[i][r][k] = Math.floor(B[i][r][k]);
            }

            //Precompute the order space
            int orderIndex = 0;

            //System.out.println("OrderIndex: " +  " " + orderIndex + " " + H);
            for (int k1 = 0; k1 < K - 1; k1++) {
                //System.out.println(k1);
                for (int k2 = 1; k2 < K; k2++) {
                    //System.out.println(k2);
                    if (k1 < k2 && orderIndex < H) {
                        //System.out.println("k1: " + k1 + " " + "k2: " + k2);
                        G[i][r][orderIndex] = (B[i][r][k1] - B[i][r][k2]) / Q;
                        orderIndex = orderIndex + 1;
                    }

                }
            }

            System.arraycopy(M[i][r], 0, A[i][r], 0, K);
            System.arraycopy(G[i][r], 0, A[i][r], K, H);

        }


        //for (int c = 0; c < C; c++)
        sigY[i] = calculateSigmoid(Predict(i));
    }

    // predict the label value vartheta_i
    public double Predict(int i) {
        //double Y_hat_ic = biasW[c];
        double Y_hat_ic = 0;
        //double y_hat_ic_shapelets = 0;
        //double y_hat_ic_orders = 0;


        for (int r = 0; r < R; r++)
            for (int kh = 0; kh < K + H; kh++)
                Y_hat_ic += A[i][r][kh] * W[r][kh];
        // Shapelet space space
//        for(int r = 0; r < R; r++)
//            for(int k = 0; k < K; k++)
//                y_hat_ic_shapelets += M[i][r][k] * W_k[c][r][k];

//                //Order space
//        for(int r = 0; r < R; r++)
//            for (int h = 0;h<H;h++)
//                y_hat_ic_orders+= G[i][r][h] * W_h[c][r][h];


        //Y_hat_ic = delta[c]*y_hat_ic_shapelets + (1- delta[c])*y_hat_ic_orders;
        return Y_hat_ic;
    }

    public void InitializeShapeletsKMeans() {
        // a multi-threaded parallel implementation of the clustering
        // on thread for each scale r, i.e. for each set of K shapelets at
        // length L_min*(r+1)
        Parallel_1x0.ForEach(rIdxs, new ForEachTask_1x0<Integer>() {
            public void iteration(Integer r) {
                try {
                    //Logging.println("Initialize Shapelets: r="+r+", J[r]="+J[r]+", L[r]="+L[r], LogLevel.DEBUGGING_LOG);

                    double[][] segmentsR = new double[ITrain * J[r]][L[r]];

                    for (int i = 0; i < ITrain; i++)
                        for (int j = 0; j < J[r]; j++)
                            for (int l = 0; l < L[r]; l++)
                                segmentsR[i * J[r] + j][l] = T.get(i, j + l);

                    // normalize segments
                    for (int i = 0; i < ITrain; i++)
                        for (int j = 0; j < J[r]; j++)
                            for (int l = 0; l < L[r]; l++)
                                segmentsR[i * J[r] + j] = utilities.StatisticalUtilities.normalize(segmentsR[i * J[r] + j]);
                    //segmentsR[i*J[r] + j] = StatisticalUtilities.Normalize(segmentsR[i*J[r] + j]);

                    Instances ins = InstanceTools.toWekaInstances(segmentsR);
                    SimpleKMeans kmeans = new SimpleKMeans();
                    kmeans.setNumClusters(K);
                    kmeans.setMaxIterations(100);
                    kmeans.setInitializeUsingKMeansPlusPlusMethod(true);
                    kmeans.buildClusterer(ins);
                    Instances centroidsWeka = kmeans.getClusterCentroids();
                    Shapelets[r] = InstanceTools.fromWekaInstancesArray(centroidsWeka, false);
                    //Shapelets[r] = kmeans.InitializeKMeansPP(segmentsR, K, 100);

                    // initialize the gradient history of shapelets
                    GradHistShapelets[r] = new double[K][L[r]];
                    for (int k = 0; k < K; k++)
                        for (int l = 0; l < L[r]; l++)
                            GradHistShapelets[r][k][l] = 0.0;


                    if (Shapelets[r] == null)
                        System.out.println("P not set");
                } catch (Exception ex) {
                    Logger.getLogger(LearnShapeletOrdersBaseline.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    public double[] Learn(String predictfile) throws FileNotFoundException {
        // initialize the data structures
        Initialize();

        List<Double> lossHistory = new ArrayList<Double>();
        lossHistory.add(Double.MIN_VALUE);

        // apply the stochastic gradient descent in a series of iterations
        for (int iter = 0; iter <= maxIter; iter++) {
            // learn the latent matrices
            LearnF();

            // measure the loss
            if (iter % 200 == 0) {
                double mcrTrain = GetMCRTrainSet();
                double mcrTest[] = GetMCRTestSet(predictfile);

                double lossTrain = AccuracyLossTrainSet();
                double lossTest = AccuracyLossTestSet();


                lossHistory.add(lossTrain);

                Logging.println("It=" + iter + ", alpha= " + alpha + ", lossTrain=" + lossTrain + ", lossTest=" + lossTest +
                                ", MCRTrain=" + mcrTrain + ", MCRTest=" + mcrTest[0] //+ ", SVM=" + mcrSVMTest
                        , Logging.LogLevel.DEBUGGING_LOG);

                //System.out.println( eta/Math.sqrt(GradHistBiasW[0]) );
                //System.out.println( eta/Math.sqrt(GradHistW[0][1][5]) );

                // if divergence is detected start from the beggining
                // at a lower learning rate
                if (Double.isNaN(lossTrain) || mcrTrain == 1.0) {
                    iter = 0;
                    eta /= 3;
                    lossHistory.clear();
                    Initialize();
                    Logging.println("Divergence detected. Restarting at eta=" + eta, Logging.LogLevel.DEBUGGING_LOG);
                }

                if (lossHistory.size() > 100)
                    if (lossTrain > lossHistory.get(lossHistory.size() - 2))
                        break;
            }
        }

        return GetMCRTestSet(predictfile);
    }

    private double[] GetMCRTestSet(String predictfile) throws FileNotFoundException {

        int numErrors = 0;

        double[] Predict = new double[ITrain + ITest];
        double[] Prob1 = new double[ITrain + ITest];
        double[] Prob2 = new double[ITrain + ITest];
        FileOutputStream fos2 = new FileOutputStream(predictfile);
        PrintStream ps2 = new PrintStream(fos2);
        for (int i = ITrain; i < ITrain + ITest; i++) {
            PreCompute(i);
            //double label_i = Sigmoid.Calculate(linearPredict(i));
            double label_i = calculateSigmoid(Predict(i));
            Prob1[i] = label_i;
            Prob2[i] = 1 - label_i;
            //prediction[i] = label_i;
            if ((Y.get(i) == 1 && label_i < 0.5) || (Y.get(i) == 0 && label_i >= 0.5)) {
                numErrors++;


                            /*if((Y.get(i) == 1 && label_i < 0.5))
                            {

                                //FN++;
                                //prediction[i] = 0;
                            }
                            else if((Y.get(i) == 0 && label_i >= 0.5))
                            {
                                //FP++;
                                //prediction[i] = 1;
                            }*/

            }
                        /*if((Y.get(i) == 1 && label_i >= 0.5) || (Y.get(i) == 0 && label_i < 0.5))
                        {
                            if((Y.get(i) == 0 && label_i < 0.5))
                            {
                                TN++;
                                prediction[i] = 0;
                            }
                            else if((Y.get(i) == 1 && label_i >= 0.5))
                            {
                                TP++;
                                prediction[i] = 1;
                        }

                        }*/

        }
        ps2.close();


        //System.out.println("Sensi = " +sensitivity);
        //System.out.println("WAccuracy = " +weighted_Accuracy);
        //System.out.println("TP = "+TP);
        // System.out.println("TN = "+TN);
        //System.out.println("FP = "+FP);
        //System.out.println("FN = "+FN);
        return new double[]{(double) numErrors / (double) ITest};
    }

    public double GetMCRTrainSet() {
        int numErrors = 0;

        for (int i = 0; i < ITrain; i++) {

            PreCompute(i);
            double label_i = calculateSigmoid(Predict(i));


            if ((Y.get(i) == 1 && label_i < 0.5) || (Y.get(i) == 0 && label_i >= 0.5))
                numErrors++;

        }


        return (double) numErrors / (double) ITrain;
    }

    public double AccuracyLoss(int i) {
        double Y_hat_ic = Predict(i);
        double sig_y_ic = calculateSigmoid(Y_hat_ic);

        return -Y.get(i) * Math.log(sig_y_ic) - (1 - Y.get(i)) * Math.log(1 - sig_y_ic);
    }

    // compute the accuracy loss of the train set
    public double AccuracyLossTrainSet() {
        double accuracyLoss = 0;

        for (int i = 0; i < ITrain; i++) {
            PreCompute(i);

            //for(int c = 0; c < C; c++)
            accuracyLoss += AccuracyLoss(i);
        }

        return accuracyLoss;
    }

    // compute the accuracy loss of the train set
    public double AccuracyLossTestSet() {
        double accuracyLoss = 0;

        for (int i = ITrain; i < ITrain + ITest; i++) {
            PreCompute(i);

            //for(int c = 0; c < C; c++)
            accuracyLoss += AccuracyLoss(i);
        }
        return accuracyLoss;
    }

    public void LearnF() {
        // parallel implementation of the learning, one thread per instance
        // up to as much threads as JVM allows
        Parallel_1x0.ForEach(instanceIdxs, new ForEachTask_1x0<Integer>() {
            public void iteration(Integer i) {
                //double regW_kConst = ((double)2.0*lambdaW_k) / ((double) ITrain*C);
                //double regW_hConst = ((double)2.0*lambdaW_h) / ((double) ITrain*C);
                double regWConst = ((double) 2.0 * lambdaW) / ((double) ITrain * C);

                double tmp1 = 0, tmp2 = 0, dLdY = 0, dMdS = 0, gradS_rkl = 0, gradW = 0, gradBiasW_c = 0;
                double eps = 0.000001;
                //double temp_X = 0, temp_Y = 0, grad_u = 0;

                //for (int c = 0; c < C; c++) {
                PreCompute(i);
                //double Y_hat_ic = Predict(i, c);
                dLdY = -(Y.get(i) - sigY[i]);


                for (int r = 0; r < R; r++) { //here starts for each scale

                    for (int k = 0; k < K; k++) {
                        // gradient with respect to W_crk
                        gradW = dLdY * M[i][r][k] + regWConst * W[r][k];

                        // add gradient square to the history
                        GradHistW[r][k] += gradW * gradW;

                        // update the weights
                        W[r][k] -= (eta / (Math.sqrt(GradHistW[r][k]) + eps)) * gradW;

                        tmp1 = (2.0 / ((double) L[r] * Psi[i][r][k]));

                        for (int l = 0; l < L[r]; l++) {
                            tmp2 = 0;
                            for (int j = 0; j < J[r]; j++)
                                tmp2 += E[i][r][k][j] * (1 + alpha * (D[i][r][k][j] - A[i][r][k])) * (Shapelets[r][k][l] - T.get(i, j + l));

                            gradS_rkl = dLdY * W[r][k] * tmp1 * tmp2;

                            // add the gradient to the history
                            GradHistShapelets[r][k][l] += gradS_rkl * gradS_rkl;

                            Shapelets[r][k][l] -= (eta / (Math.sqrt(GradHistShapelets[r][k][l]) + eps))
                                    * gradS_rkl;

                        }
                    }
//                        for(int h = 0;h <H;h++){
//                            gradW_h = dLdY*(1-delta[c])*G[i][r][h] + regW_hConst*W_h[c][r][h];
//                            GradHistW_h[c][r][h] += gradW_h*gradW_h;
//                            temp_Y = G[i][r][h]*W_h[c][r][h];
//                            W_h[c][r][h] -= (eta / ( Math.sqrt(GradHistW_h[c][r][h]) + eps))*gradW_h;
//
//                        }


                }

                // the gradient
                gradBiasW_c = dLdY;
//                    dydDelta = temp_X-temp_Y;
//                    grad_u = Math.exp(u[c])*dLdY*dydDelta;
//
//                    // add the gradient to the history
                GradHistBiasW += gradBiasW_c * gradBiasW_c;
//		    GradHist_u[c]+=grad_u*grad_u;
//                    //biasW[c] -= (eta / ( Math.sqrt(GradHistBiasW[c]) + eps))*gradBiasW_c;
//                    u[c] -= (eta / ( Math.sqrt(GradHist_u[c]) + eps))*grad_u;
//                    delta[c] = Math.exp(u[c]);
//                    delta[c] = calculateSigmoid(delta[c]);

                //}
            }

        });

    }

    public void PrintProjectedData() throws FileNotFoundException {
        //FileOutputStream fos4 = new FileOutputStream(outputfile1);
        //PrintStream ps4 = new PrintStream(fos4);
        int r = 0, c = 0;

        System.out.print("Data= [ ");

        for (int i = 0; i < ITrain + ITest; i++) {
            PreCompute(i);

            //System.out.print(Y_b.get(i, c) + " ");

            for (int k = 0; k < K; k++) {
                System.out.print(M[i][r][k] + " ");
                System.out.print(B[i][r][k] + " ");
                //ps4.print(M[i][r][k]+ " ");
            }

            for (int h = 0; h < H; h++) {
                System.out.print(G[i][r][h] + " ");
            }
            //ps4.println();

            System.out.println(";");
        }

        //ps4.close();
        System.out.println("];");
    }

    public void PrintShapeletsAndWeights(String outputfile) throws FileNotFoundException {
        FileOutputStream fos = new FileOutputStream(outputfile);
        PrintStream ps = new PrintStream(fos);
        for (int r = 0; r < R; r++) {
            for (int k = 0; k < K; k++) {
                //System.out.print("Shapelets("+r+","+k+")= [ ");
                //ps.print("Shapelets("+r+","+k+")= [ ");

                for (int l = 0; l < L[r]; l++) {
                    //System.out.print(Shapelets[r][k][l] + " ");
                    ps.print(Shapelets[r][k][l] + " ");
                }

                //System.out.println("]");
                ps.println();
            }
        }

        //for(int c = 0; c < C; c++){
        for (int r = 0; r < R; r++) {
            //System.out.print("W("+c+","+r+")= [ ");
            //ps.print("W("+c+","+r+")= [ ");

            for (int k = 0; k < K; k++) {
                //System.out.print(W[c][r][k] + " ");
                // ps.print(W[c][r][k] + " ");
            }

            //System.out.print(biasW[c] + " ");
            //ps.print(biasW[c] + " ");
            //System.out.println("]");
            //ps.println("]");
        }
        //}
        ps.close();
    }

    public static void main(String[] args) throws FileNotFoundException, IOException {
        System.out.println("LTS + orders");
        //main outer directory for Dataset Selection
        String maindirectory = "/home/a3d/Desktop/Shoumik_experiments/dataset/ECML2020/Real/single3/";
        String sp = File.separator;
        File file = new File(maindirectory);
        String[] datasets = file.list();
        //String[] names = Arrays.copyOfRange(names_raw, 1, names_raw.length);1

        for (String name : datasets) {
            System.out.println("DATASET : " + name);


            //Load the initial sample data
            Instances test = utilities.ClassifierTools.loadData(maindirectory + name + sp + name + "_TEST.arff");
            Instances train = utilities.ClassifierTools.loadData(maindirectory + name + sp + name + "_TRAIN.arff");
            String place = maindirectory + name + sp;


            //File handling preliminaries
            String outfile = place + name + "_LearnOrders_Results_baseline.txt";
            FileOutputStream fos = new FileOutputStream(outfile);
            PrintStream ps = new PrintStream(fos);

            //String outfile4 = place + name+"_numTrain_numTest.txt";
            //FileOutputStream fos4 = new FileOutputStream(outfile4);
            //PrintStream ps4 = new PrintStream(fos4);
            //ps4.print("seed " + " numTrain " + "numTest" );
            //ps4.println();
            int numofSeeds = 10;
            int counter = 0;
            double[] meanAccuracy = new double[numofSeeds];
            double[] meanTrainAccuracy = new double[numofSeeds];
            double[] trainTimes = new double[numofSeeds];
            int numOfShapelets = 0;
            int numOfOrders = 0;
            //Create resample datasets for seeds 0 - 10
            for (long seed = 0; seed < numofSeeds; seed++) {
                System.out.println("seed:" + seed);

                //Java class object
                LearnShapeletOrdersBaselineBinaryClassification lso = new LearnShapeletOrdersBaselineBinaryClassification();

                //## Resample the dataset with same distribution of classes
                Instances[] resampled = InstanceTools.resampleTrainAndTestInstances(train, test, seed);

                //convert the training set into a 2D Matrix
                //lso.train2D = InstanceTools.fromWekaInstancesArray(resampled[0], true);
                //lso.test2D =  InstanceTools.fromWekaInstancesArray(resampled[1], true);

                DataSet trainSet = new DataSet(resampled[0]);
                DataSet testSet = new DataSet(resampled[1]);


                // normalize the data instance
                trainSet.NormalizeDatasetInstances();
                testSet.NormalizeDatasetInstances();

                // predictor variables T
                Matrix T = new Matrix();
                T.LoadDatasetFeatures(trainSet, false);
                T.LoadDatasetFeatures(testSet, true);
                // outcome variable O
                Matrix O = new Matrix();
                O.LoadDatasetLabels(trainSet, false);
                O.LoadDatasetLabels(testSet, true);

                // set the time series and labels
                lso.T = T;
                lso.Y = O;
                //System.out.println(lso.T);

                // initialize the sizes of data structures
                lso.ITrain = trainSet.GetNumInstances();
                lso.ITest = testSet.GetNumInstances();
                lso.Q = T.getDimColumns();


                // set the learn rate and the number of iterations and other hyper parameters
                double L = 0.1;
                //double lambdaW_k  = 0.01;
                //double lambdaW_h  = 0.01;
                double lambdaW = 0.01;
                int maxEpochs = 1000;
                double K = -1;
                double eta = 0.1;
                double alpha = -100;
                int R = 3;

                // set predefined parameters if none set
                if (R < 0) R = 3;
                if (L < 0) L = 0.15;
                if (eta < 0) eta = 0.01;
                if (alpha > 0) alpha = -30;
                if (maxEpochs < 0) maxEpochs = 1000;


                lso.maxIter = maxEpochs;
                // set te number of patterns
                lso.K = (int) (K * T.getDimColumns());
                lso.L_min = (int) (L * T.getDimColumns());
                lso.R = R;
                // set the regularization parameter
                //lso.lambdaW_h = lambdaW_h;
                //lso.lambdaW_k = lambdaW_k;
                lso.lambdaW = lambdaW;
                lso.eta = eta;
                lso.alpha = alpha;
                trainSet.ReadNominalTargets();
                lso.nominalLabels = new ArrayList<Double>(trainSet.nominalLabels);

                // learn the model
                long startMethodTime = System.currentTimeMillis();
                lso.Learn(outfile);
                double elapsedMethodTime = System.currentTimeMillis() - startMethodTime;
                trainTimes[counter] = elapsedMethodTime / 1000; // in second

                double trainSetError = lso.GetMCRTrainSet();
                double trainSetAccuracy = 1 - trainSetError;
                double[] arrayRet = lso.GetMCRTestSet(outfile);
                double accuracy = 1 - arrayRet[0];
                System.out.println("Train Set Accuracy for seed " + seed + ":" + trainSetAccuracy);
                meanTrainAccuracy[counter] = trainSetAccuracy;
                System.out.println("Test Set Accuracy for seed " + seed + ":" + accuracy);
                meanAccuracy[counter] = accuracy;
                numOfShapelets = lso.K;
                numOfOrders = lso.H;
                counter++;


                ////File handling processing preliminaries
                //String outfile2 = place + "seed_"+seed+"_shapeletsOnly.txt";
                //String outfile3 = place + "seed_"+seed+"_shapeletsAndOrders.txt";
                //FileOutputStream fos2 = new FileOutputStream(outfile2);
                //FileOutputStream fos3 = new FileOutputStream(outfile3);
                //PrintStream ps2 = new PrintStream(fos2);
                //PrintStream ps3 = new PrintStream(fos3);

                //int numInstance = lso.ITrain+lso.ITest;
                //ps4.print(seed + " ");
                //ps4.print(lso.ITrain + " ");
                //ps4.print(lso.ITest);
                //ps4.println();


            }
            //ps4.close();
            double accuracyMean = StatisticalUtilities.mean(meanAccuracy, false);
            double stdMean = StatisticalUtilities.standardDeviation(meanAccuracy, false, accuracyMean);

            double trainAccuracyMean = StatisticalUtilities.mean(meanTrainAccuracy, false);
            double trainstdMean = StatisticalUtilities.standardDeviation(meanTrainAccuracy, false, trainAccuracyMean);

            double trainTime = StatisticalUtilities.mean(trainTimes, false);
            double trainStd = StatisticalUtilities.standardDeviation(trainTimes, false, trainTime);

            System.out.println(trainAccuracyMean + "\u00B1" + trainstdMean);
            System.out.println(accuracyMean + "\u00B1" + stdMean);
            System.out.println(trainTime + "\u00B1" + trainStd);
            System.out.println("K  = " + numOfShapelets + " " + "H = " + numOfOrders);
            ps.print("Average Train set Accuracy: " + trainAccuracyMean + "\u00B1" + trainstdMean);
            ps.print("\nAverage Test set Accuracy: " + accuracyMean + "\u00B1" + stdMean);
            ps.print("\nTraining Time: " + trainTime + "\u00B1" + trainStd);
            ps.print("\n Number of Shapelets: " + numOfShapelets + " " + "Number of Orders:  " + numOfOrders);

            ps.close();

        }


    }
}
