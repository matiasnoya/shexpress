package com.shopexpress.usuarios.config;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;

/**
 * Dos "caras" de MongoDB segun el requerimiento de consistencia:
 * <ul>
 *   <li>{@code mongoTemplate} (escrituras, CP): lee del primario y escribe con w:majority.</li>
 *   <li>{@code lecturaMongoTemplate} (lecturas, AP): secondaryPreferred, acepta consistencia eventual.</li>
 * </ul>
 */
@Configuration
public class MongoConfig {

    public static final String LECTURA = "lecturaMongoTemplate";

    @Bean
    @Primary
    MongoTemplate mongoTemplate(MongoDatabaseFactory factory, MongoConverter converter) {
        MongoTemplate t = new MongoTemplate(factory, converter);
        t.setWriteConcern(WriteConcern.MAJORITY);
        t.setReadPreference(ReadPreference.primary());
        return t;
    }

    @Bean(LECTURA)
    MongoTemplate lecturaMongoTemplate(MongoDatabaseFactory factory, MongoConverter converter) {
        MongoTemplate t = new MongoTemplate(factory, converter);
        t.setReadPreference(ReadPreference.secondaryPreferred());
        return t;
    }

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory, TransactionOptions.builder()
                .readConcern(ReadConcern.MAJORITY)
                .writeConcern(WriteConcern.MAJORITY)
                .readPreference(ReadPreference.primary())
                .build());
    }
}
