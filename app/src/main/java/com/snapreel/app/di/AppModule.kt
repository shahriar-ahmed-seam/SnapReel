package com.snapreel.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.snapreel.app.data.preferences.SETTINGS_DATASTORE_NAME
import com.snapreel.app.util.thumbnail.AndroidThumbnailGenerator
import com.snapreel.app.util.thumbnail.ThumbnailDiskCache
import com.snapreel.app.util.thumbnail.ThumbnailGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** A process-lifetime scope for work that must outlive any screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Builds the settings DataStore that [AppModule] provides. [scope] owns it: DataStore allows one
 * active instance per file, and cancelling [scope] releases the file (tests use this to get a
 * fresh instance on the same file).
 */
fun createSettingsDataStore(
    context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> =
    createSettingsDataStore(scope) { context.preferencesDataStoreFile(SETTINGS_DATASTORE_NAME) }

/** The same settings DataStore (same corruption handler) on the file [produceFile] returns. */
fun createSettingsDataStore(
    scope: CoroutineScope,
    produceFile: () -> File,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = produceFile,
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The one settings DataStore, on the same `snapreel_settings` file as every earlier version,
     * so existing settings and Recents are kept. A corrupt file is replaced with empty preferences.
     */
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore(context)

    /** `noBackupFilesDir/video_thumbs`, 100 MiB: internal, persistent, excluded from Auto Backup. */
    @Provides
    @Singleton
    fun provideThumbnailDiskCache(@ApplicationContext context: Context): ThumbnailDiskCache =
        ThumbnailDiskCache(File(context.noBackupFilesDir, ThumbnailDiskCache.DIRECTORY_NAME))

    @Provides
    @Singleton
    fun provideThumbnailGenerator(impl: AndroidThumbnailGenerator): ThumbnailGenerator = impl
}
