package com.example.learning

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import java.time.LocalDate

object AppViewModelProvider {
    val Factory = viewModelFactory {

        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)
            HomeViewModel(app.repos.transitInfo)
        }
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)
            val args: Trips = createSavedStateHandle().toRoute()
            TripsViewModel(args.tripId, args.stopId, LocalDate.parse(args.date), app.repos.transitInfo)
        }
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)
            FilterViewModel(app.repos.transitInfo)
        }
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)
            PickStopViewModel(app.repos.transitInfo)
        }
        initializer {
            val app = (this[APPLICATION_KEY] as LearningApplication)
            SavedStopsViewModel(app.repos.transitInfo)
        }
    }
}
