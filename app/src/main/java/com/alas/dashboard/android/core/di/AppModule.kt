package com.alas.dashboard.android.core.di

import android.content.Context
import androidx.room.Room
import com.alas.dashboard.android.core.database.DashboardDatabase
import com.alas.dashboard.android.core.database.ResourceDao
import com.alas.dashboard.android.core.network.ApiFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DashboardDatabase =
        Room.databaseBuilder(
            context,
            DashboardDatabase::class.java,
            "dashboard.db",
        ).build()

    @Provides
    fun provideDao(database: DashboardDatabase): ResourceDao = database.resourceDao()

    @Provides
    @Singleton
    fun provideApiFactory(): ApiFactory = ApiFactory()
}
