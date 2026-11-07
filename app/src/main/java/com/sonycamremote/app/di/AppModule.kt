package com.sonycamremote.app.di

import android.content.Context
import androidx.room.Room
import com.sonycamremote.app.data.camera.CameraRepository
import com.sonycamremote.app.data.camera.CameraSessionManager
import com.sonycamremote.app.data.db.AppDatabase
import com.sonycamremote.app.data.db.PresetDao
import com.sonycamremote.app.data.db.SavedCameraDao
import com.sonycamremote.app.data.db.SessionEventDao
import com.sonycamremote.engine.diagnostics.DiagnosticsLog
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun diagnosticsLog(): DiagnosticsLog = DiagnosticsLog()

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "sonycamremote.db").build()

    @Provides fun savedCameraDao(db: AppDatabase): SavedCameraDao = db.savedCameras()
    @Provides fun presetDao(db: AppDatabase): PresetDao = db.presets()
    @Provides fun sessionEventDao(db: AppDatabase): SessionEventDao = db.sessionEvents()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun cameraRepository(impl: CameraSessionManager): CameraRepository
}
