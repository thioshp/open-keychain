package org.sufficientlysecure.keychain.model;


import java.util.Date;

import android.support.annotation.NonNull;

import com.squareup.sqldelight.ColumnAdapter;

final class CustomColumnAdapters {

    private CustomColumnAdapters() { }

    static final ColumnAdapter<Date,Long> DATE_ADAPTER = new ColumnAdapter<Date,Long>() {
        @NonNull
        @Override
        public Date decode(Long databaseValue) {
            return new Date(databaseValue);
        }

        @Override
        public Long encode(@NonNull Date value) {
            return value.getTime();
        }
    };

}
