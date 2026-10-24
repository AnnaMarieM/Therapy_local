package com.therapylocal.app.ui.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therapylocal.app.ui.models.Patient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Holds the client list for the UI. Reloads from the memory store on [refresh]; patients added in the UI only live in memory for now. */
class ClientsViewModel(app: Application) : AndroidViewModel(app) {
    val repo = ClientRepository(app)
    private val local = ArrayList<Patient>()
    private val _patients = MutableStateFlow<List<Patient>>(emptyList())
    val patients: StateFlow<List<Patient>> = _patients.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = repo.patients()
            _patients.value = local.filter { l -> stored.none { it.id == l.id } } + stored
        }
    }

    /** TODO: persist new clients; until then they vanish on restart. */
    fun addLocal(p: Patient) {
        local.add(0, p)
        _patients.value = listOf(p) + _patients.value
    }

    /** In-memory update of one patient, used for the mock fallback data only. */
    fun replaceInMemory(p: Patient) {
        _patients.value = _patients.value.map { if (it.id == p.id) p else it }
    }
}
