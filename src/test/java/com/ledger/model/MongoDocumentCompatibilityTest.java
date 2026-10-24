package com.ledger.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;

class MongoDocumentCompatibilityTest {

    @Test
    void mapsModelsToExpectedMongoCollectionNames() {
        assertThat(User.class.getAnnotation(Document.class).collection()).isEqualTo("users");
        assertThat(Account.class.getAnnotation(Document.class).collection()).isEqualTo("accounts");
        assertThat(Ledger.class.getAnnotation(Document.class).collection()).isEqualTo("ledgers");
        assertThat(Transaction.class.getAnnotation(Document.class).collection()).isEqualTo("transactions");
        assertThat(BlacklistedToken.class.getAnnotation(Document.class).collection()).isEqualTo("tokenblacklists");
    }

    @Test
    void keepsObjectIdReferencesAndBsonDoubleAmounts() throws Exception {
        assertThat(field(Account.class, "user").getType()).isEqualTo(ObjectId.class);
        assertThat(field(Ledger.class, "account").getType()).isEqualTo(ObjectId.class);
        assertThat(field(Ledger.class, "transaction").getType()).isEqualTo(ObjectId.class);
        assertThat(field(Transaction.class, "fromAccount").getType()).isEqualTo(ObjectId.class);
        assertThat(field(Transaction.class, "toAccount").getType()).isEqualTo(ObjectId.class);
        assertThat(field(Ledger.class, "amount").getType()).isEqualTo(BigDecimal.class);
        assertThat(field(Ledger.class, "amount").getAnnotation(
                org.springframework.data.mongodb.core.mapping.Field.class).targetType()).isEqualTo(FieldType.DOUBLE);
        assertThat(field(Transaction.class, "amount").getAnnotation(
                org.springframework.data.mongodb.core.mapping.Field.class).targetType()).isEqualTo(FieldType.DOUBLE);
    }

    @Test
    void keepsBlacklistTtlAtThreeDays() throws Exception {
        Indexed index = field(BlacklistedToken.class, "createdAt").getAnnotation(Indexed.class);

        assertThat(index.expireAfter()).isEqualTo("PT72H");
        assertThat(index.name()).isEqualTo("createdAt_1");
    }

    private Field field(Class<?> type, String name) throws NoSuchFieldException {
        return type.getDeclaredField(name);
    }
}
