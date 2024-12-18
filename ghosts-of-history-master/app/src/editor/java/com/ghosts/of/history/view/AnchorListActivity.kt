package com.ghosts.of.history.view

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghosts.of.history.R
import com.ghosts.of.history.common.models.ItemAdapter
import com.ghosts.of.history.common.models.ItemModel
import com.ghosts.of.history.databinding.ActivityAnchorListBinding
import com.ghosts.of.history.model.AnchorData
import com.ghosts.of.history.viewmodel.AnchorListActivityViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class AnchorListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnchorListBinding
    private val job: Job = Job()
    private val uiScope: CoroutineScope = CoroutineScope(Dispatchers.Main + job)
    private val viewModel: AnchorListActivityViewModel by viewModels {
        AnchorListActivityViewModel.Factory
    }

//    val registerEditResultLauncher: ActivityResultLauncher<Intent> =
//        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
//            if (result.resultCode == Activity.RESULT_OK) {
//                uiScope.launch {
//                    val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
//                    recyclerView.adapter = ItemAdapter(fetchItems())
//                }
//            }
//        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Firebase.auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityAnchorListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val addMarkerButton = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.add_marker_button)
        addMarkerButton.setOnClickListener {
            startActivity(Intent(this, EditAnchorActivity::class.java))
        }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        val dividerItemDecoration = DividerItemDecoration(
            recyclerView.context, layoutManager.orientation
        )
        recyclerView.addItemDecoration(dividerItemDecoration)


        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.anchorsDataState
                            .collect {
                                val items: List<ItemModel> = it.anchorsData.map { entry ->
                                    mapAnchorDataToItemModel(entry.value)
                                }
                                recyclerView.adapter = ItemAdapter(items, viewModel)
                                if (items.isNotEmpty()) {
                                    binding.anchorsProgressBar.visibility = View.GONE
                                }
                            }
                }
            }
        }

//        uiScope.launch {
//            val items = fetchItems()
//            recyclerView.adapter = ItemAdapter(items)
//        }

        setSupportActionBar(findViewById(R.id.toolbar))
        binding.toolbarLayout.title = "Exhibit List"

    }

    private fun mapAnchorDataToItemModel(anchorData: AnchorData): ItemModel {
        return ItemModel(
                anchorData,
                uiScope,
                applicationContext
        )
    }

//    private suspend fun fetchItems(): List<ItemModel> = withContext(Dispatchers.IO) {
//        val anchorsData = getAnchorsDataFromFirebase()
//
//        anchorsData.map { anchorData ->
//            ItemModel(
//                    anchorData,
//                    uiScope,
//                    applicationContext
//            )
//        }
//    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}