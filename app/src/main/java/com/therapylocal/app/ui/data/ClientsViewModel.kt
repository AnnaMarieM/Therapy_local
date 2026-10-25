package com.therapylocal.app.ui.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therapylocal.app.memory.JobStatus
import com.therapylocal.app.memory.MemoryQueue
import com.therapylocal.app.ui.models.Patient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Holds the client list for the UI. Reloads from the memory store on [refresh]. */
class ClientsViewModel(app: Application) : AndroidViewModel(app) {
    val repo = ClientRepository(app)
    private val _patients = MutableStateFlow<List<Patient>>(emptyList())
    val patients: StateFlow<List<Patient>> = _patients.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            MemoryQueue.state
                .map { state -> state.jobs.filter { it.status == JobStatus.Done }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect {
                    refresh()
                }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _patients.value = repo.patients()
        }
    }

    /** Creates the client in the memory store and returns it (with its real id). */
    fun create(name: String, age: Int?, occupation: String): Patient {
        val p = repo.createClient(name, age, occupation)
        _patients.value = listOf(p) + _patients.value.filter { it.id != p.id }
        return p
    }

    fun delete(id: String) {
        _patients.value = _patients.value.filter { it.id != id }
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteClient(id)
            refresh()
        }
    }
}
