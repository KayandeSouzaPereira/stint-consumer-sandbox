package io.stintflow.sandbox.scenarios.support;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** The AWS clients of a scenario: always floci, always its dummy credentials {@code test}/{@code test}. */
public final class Floci implements AutoCloseable {

    public static final String ACCOUNT = "000000000000";

    private static final StaticCredentialsProvider DUMMY =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

    public final SqsClient sqs = SqsClient.builder().endpointOverride(Scenario.FLOCI).region(Region.US_EAST_1)
            .credentialsProvider(DUMMY).httpClient(UrlConnectionHttpClient.create()).build();
    public final DynamoDbClient ddb = DynamoDbClient.builder().endpointOverride(Scenario.FLOCI).region(Region.US_EAST_1)
            .credentialsProvider(DUMMY).httpClient(UrlConnectionHttpClient.create()).build();
    public final S3Client s3 = S3Client.builder().endpointOverride(Scenario.FLOCI).region(Region.US_EAST_1)
            .forcePathStyle(true).credentialsProvider(DUMMY).httpClient(UrlConnectionHttpClient.create()).build();
    public final EventBridgeClient eventBridge = EventBridgeClient.builder().endpointOverride(Scenario.FLOCI)
            .region(Region.US_EAST_1).credentialsProvider(DUMMY).httpClient(UrlConnectionHttpClient.create()).build();
    public final SchedulerClient scheduler = SchedulerClient.builder().endpointOverride(Scenario.FLOCI)
            .region(Region.US_EAST_1).credentialsProvider(DUMMY).httpClient(UrlConnectionHttpClient.create()).build();

    public static String queueUrl(String name) {
        return Scenario.FLOCI + "/" + ACCOUNT + "/" + name;
    }

    @Override
    public void close() {
        sqs.close();
        ddb.close();
        s3.close();
        eventBridge.close();
        scheduler.close();
    }
}
