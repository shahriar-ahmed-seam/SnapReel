package com.snapreel.app.di

import com.snapreel.app.util.UpdateManager
import com.snapreel.app.util.UpdateSource
import com.snapreel.app.util.update.ApkFactsSource
import com.snapreel.app.util.update.ApkInspector
import com.snapreel.app.util.update.PackageInstallGateway
import com.snapreel.app.util.update.UpdateInstaller
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Production implementations behind the update coordinator's seams. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    abstract fun bindUpdateSource(impl: UpdateManager): UpdateSource

    @Binds
    abstract fun bindApkFactsSource(impl: ApkInspector): ApkFactsSource

    @Binds
    abstract fun bindPackageInstallGateway(impl: UpdateInstaller): PackageInstallGateway
}
