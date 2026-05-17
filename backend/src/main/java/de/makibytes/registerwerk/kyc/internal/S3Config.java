package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.internal.S3Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties props) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(StringUtils.hasText(props.getRegion()) ? props.getRegion() : "eu-central-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                StringUtils.hasText(props.getAccessKey()) ? props.getAccessKey() : "changeme",
                                StringUtils.hasText(props.getSecretKey()) ? props.getSecretKey() : "changeme"
                        )
                ));

        if (StringUtils.hasText(props.getEndpoint())) {
            builder.endpointOverride(URI.create(props.getEndpoint()))
                   .forcePathStyle(true);
        }

        return builder.build();
    }
}
