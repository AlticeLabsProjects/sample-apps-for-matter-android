package com.google.homesampleapp.data

import android.webkit.CookieManager
import com.google.homesampleapp.websocket.impl.WebSocketRepositoryImpl.Companion.USER_AGENT
import com.google.homesampleapp.websocket.impl.WebSocketRepositoryImpl.Companion.USER_AGENT_STRING
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    companion object {

        @Provides
        @Singleton
        fun providesOkHttpClient(): OkHttpClient  {

            val builder = OkHttpClient.Builder()

            builder.addNetworkInterceptor {
                it.proceed(
                    it.request()
                        .newBuilder()
                        .header(USER_AGENT, USER_AGENT_STRING)
                        .build()
                )
            }
            // Only deal with cookies when on non wear device and for now I don't have a better
            // way to determine if we are really on wear os....
            // TODO: Please fix me.
            var cookieManager: CookieManager? = null
            try {
                cookieManager = CookieManager.getInstance()
            } catch (e: Exception) {
                // Noop
            }
            if (cookieManager != null) {
                builder.cookieJar(CookieJarCookieManagerShim())
            }
            builder.callTimeout(30L, TimeUnit.SECONDS)
            builder.readTimeout(30L, TimeUnit.SECONDS)

            //tlsHelper.setupOkHttpClientSSLSocketFactory(builder)

            return builder.build()
        }

    }
}
