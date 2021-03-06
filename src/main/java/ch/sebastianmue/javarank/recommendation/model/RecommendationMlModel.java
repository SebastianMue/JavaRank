package ch.sebastianmue.javarank.recommendation.model;

import ch.sebastianmue.javarank.recommendation.data.InputRating;
import ch.sebastianmue.javarank.recommendation.data.RDDHelper;
import ch.sebastianmue.javarank.recommendation.exceptions.ErrorInDataSourceException;
import ch.sebastianmue.javarank.recommendation.exceptions.ModelNotReadyException;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.mllib.recommendation.ALS;
import org.apache.spark.mllib.recommendation.MatrixFactorizationModel;
import org.apache.spark.mllib.recommendation.Rating;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Model Class which provides the predictions
 */
public class RecommendationMlModel {

    private static final String SPARK_APP_NAME = "Recommendation Engine";
    private static final String SPARK_MASTER = "local";
    private final ReentrantLock trainingLock = new ReentrantLock();
    private final ReentrantReadWriteLock mutex = new ReentrantReadWriteLock();
    private final JavaSparkContext javaSparkContext = new JavaSparkContext(SPARK_MASTER, SPARK_APP_NAME);
    private final ALS als = new ALS();
    private final RDDHelper rddHelper = new RDDHelper(javaSparkContext);
    private MatrixFactorizationModel model;

    private volatile AtomicInteger modelNumber = new AtomicInteger(0);
    private volatile boolean modelIsReady = false;

    /**
     * Constructor, which allows to retrain and replace the model.
     * The given Callable will be used to get the ch.javarank.data for the model.
     *
     * @param inputRatings callable to get the Input ratings for the Model
     * @param retrainTime  Time to wait before training a new model
     * @param initialDelay Time to wait before training the first model
     */
    public RecommendationMlModel(Callable<Collection<InputRating>> inputRatings, long retrainTime, long initialDelay) {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(() -> asyncTrainModel(inputRatings), initialDelay, retrainTime, TimeUnit.SECONDS);
    }

    /**
     * Constructor, which trains the model once. provide a callable, if you want your model to improve periodically.
     *
     * @param inputRatings
     */
    public RecommendationMlModel(Collection<InputRating> inputRatings) {
        asyncTrainModel(inputRatings);
    }

    /**
     * @return true if a model is ready. Just can be false, if the module was new init and the first model is not ready yet.
     */
    public boolean isModelReady() {
        return modelIsReady;
    }

    /**
     * Provides a prediction for the given parameters
     *
     * @param userId
     * @param eventId
     * @return the prediction, which rating the used is likely to give. If either the user or the product is unknown, it will return empty
     * @throws ModelNotReadyException
     */
    public Optional<Double> getInterestPrediction(Integer userId, Integer eventId) throws ModelNotReadyException {
        if (!modelIsReady)
            throw new ModelNotReadyException();
        mutex.readLock().lock();
        Optional<Double> prediction;
        try {
            prediction = Optional.of(model.predict(userId, eventId));
        } catch (IllegalArgumentException e) {
            prediction = Optional.empty();
        } finally {
            mutex.readLock().unlock();
        }
        return prediction;
    }

    /**
     * Close the sparkContext and set the resources free
     */
    public void close() {
        javaSparkContext.close();
    }

    private void asyncTrainModel(Callable<Collection<InputRating>> inputRatings) {
        try {
            asyncTrainModel(inputRatings.call());
        } catch (Exception e) {
            throw new ErrorInDataSourceException(e);
        }
    }


    private void asyncTrainModel(Collection<InputRating> inputRatings) {
        Thread thread = new Thread(() -> {
            if (trainingLock.isLocked())
                return;
            trainingLock.lock();
            trainModel(inputRatings);
            trainingLock.unlock();
        });
        thread.start();
    }

    private void trainModel(Collection<InputRating> ratings) {
        JavaRDD<Rating> ratingRDD = rddHelper.getRddFromCollection(createSparkRating(ratings)).cache();
        if (ratingRDD.isEmpty())
            return;
        mutex.writeLock().lock();
        model = als.setRank(10).setIterations(10).run(ratingRDD);
        mutex.writeLock().unlock();
        modelNumber.incrementAndGet();
        modelIsReady = true;

    }


    private List<Rating> createSparkRating(Collection<InputRating> inputRatings) {
        return inputRatings
                .stream()
                .map(ir -> new Rating(ir.getUserId(), ir.getProductId(), ir.getRating()))
                .collect(Collectors.toList());
    }

    public Integer getModelNumber() {
        return modelNumber.get();
    }
}
