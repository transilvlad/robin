package com.mimecast.robin.http;

import kotlin.jvm.functions.Function0;
import kotlin.reflect.KClass;
import okhttp3.*;
import okio.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MockOkHttpClient extends OkHttpClient {
    Response response;

    public MockOkHttpClient(Response response) {
        this.response = response;
    }

    @NotNull
    @Override
    public Call newCall(@NotNull Request request) {
        return new MockCall(response);
    }
}

class MockCall implements Call {

    Response response;

    public MockCall(Response response) {
        this.response = response;
    }

    @Override
    public void cancel() {

    }

    @NotNull
    @Override
    public Request request() {
        return response.request();
    }

    @NotNull
    @Override
    public Response execute() {
        return response;
    }

    @Override
    public void enqueue(@NotNull Callback callback) {

    }

    @Override
    public boolean isExecuted() {
        return true;
    }

    @Override
    public boolean isCanceled() {
        return false;
    }

    @NotNull
    @Override
    public Timeout timeout() {
        return new Timeout();
    }

    @NotNull
    @Override
    public Call clone() {
        return null;
    }

    @Nullable
    @Override
    public <T> T tag(@NotNull KClass<T> kClass) {
        return null;
    }

    @Nullable
    @Override
    public <T> T tag(@NotNull Class<? extends T> aClass) {
        return null;
    }

    @NotNull
    @Override
    public <T> T tag(@NotNull KClass<T> kClass, @NotNull Function0<? extends T> function0) {
        return null;
    }

    @NotNull
    @Override
    public <T> T tag(@NotNull Class<T> aClass, @NotNull Function0<? extends T> function0) {
        return null;
    }
}
