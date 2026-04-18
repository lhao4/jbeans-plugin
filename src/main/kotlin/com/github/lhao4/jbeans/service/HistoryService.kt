package com.github.lhao4.jbeans.service

import com.github.lhao4.jbeans.history.HistoryRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "JBeansHistory", storages = [Storage("jbeans-history.xml")])
class HistoryService(private val project: Project) : PersistentStateComponent<HistoryService.State> {

    class State {
        var recordsJson: String = "[]"
    }

    private val gson = Gson()
    private var records: MutableList<HistoryRecord> = mutableListOf()
    private val listeners = mutableListOf<() -> Unit>()

    override fun getState() = State().also { it.recordsJson = gson.toJson(records) }

    override fun loadState(state: State) {
        val type = object : TypeToken<MutableList<HistoryRecord>>() {}.type
        records = runCatching<MutableList<HistoryRecord>> { gson.fromJson(state.recordsJson, type) }.getOrNull() ?: mutableListOf()
    }

    fun add(record: HistoryRecord) {
        records.add(0, record)
        if (records.size > 200) records = records.take(200).toMutableList()
        notifyListeners()
    }

    fun getAll(): List<HistoryRecord> = records.toList()

    fun toggleStar(id: String) {
        val i = records.indexOfFirst { it.id == id }
        if (i >= 0) {
            records[i] = records[i].copy(starred = !records[i].starred)
            notifyListeners()
        }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)

    private fun notifyListeners() = listeners.forEach { it() }
}
