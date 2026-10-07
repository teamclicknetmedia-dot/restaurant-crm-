package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.BrandRegistration
import com.example.data.model.Order
import com.example.data.model.Restaurant
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CrmRepository(private val context: Context) {

    companion object {
        private const val TAG = "CrmRepository"
    }

    private val db: FirebaseFirestore by lazy {
        val dbIdRes = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        if (dbIdRes != 0) {
            FirebaseFirestore.getInstance(context.getString(dbIdRes))
        } else {
            FirebaseFirestore.getInstance()
        }
    }

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid ?: throw IllegalStateException("User must be authenticated before performing database operations.")
    }

    // Default sample restaurants loaded if Firestore collection is initially empty
    private val initialSampleRestaurants = listOf(
        Restaurant(
            id = "rest_001",
            name = "Clicknet Bistro & Lounge",
            ownerEmail = "team.clicknetmedia@gmail.com",
            phone = "+91 98765 43210",
            address = "Sector 18, Commercial Hub, Noida",
            tablesCount = 24,
            status = "active",
            subscriptionPlan = "Enterprise",
            revenueMonthly = 485000.0,
            activeOrdersCount = 8
        ),
        Restaurant(
            id = "rest_002",
            name = "Royal Spice Kitchen",
            ownerEmail = "royal.spice@clicknetmedia.com",
            phone = "+91 98111 22334",
            address = "MG Road, Indiranagar, Bangalore",
            tablesCount = 18,
            status = "active",
            subscriptionPlan = "Enterprise",
            revenueMonthly = 320000.0,
            activeOrdersCount = 5
        ),
        Restaurant(
            id = "rest_003",
            name = "Coastal Bay Diner",
            ownerEmail = "coastalbay@clicknetmedia.com",
            phone = "+91 97222 33445",
            address = "Bandra West, Mumbai",
            tablesCount = 15,
            status = "active",
            subscriptionPlan = "Professional",
            revenueMonthly = 275000.0,
            activeOrdersCount = 3
        )
    )

    private val initialSampleOrders = listOf(
        Order(id = "ORD-101", restaurantId = "rest_001", tableNumber = "T-04", itemsSummary = "2x Truffle Pasta, 1x Mocktail", totalAmount = 1450.0, status = "Preparing"),
        Order(id = "ORD-102", restaurantId = "rest_001", tableNumber = "T-09", itemsSummary = "4x Dum Biryani, 4x Mojito", totalAmount = 2800.0, status = "Served"),
        Order(id = "ORD-103", restaurantId = "rest_002", tableNumber = "T-02", itemsSummary = "1x Paneer Tikka Platter", totalAmount = 650.0, status = "Pending"),
        Order(id = "ORD-104", restaurantId = "rest_003", tableNumber = "T-06", itemsSummary = "2x Grilled Fish, 2x Fresh Lime", totalAmount = 1950.0, status = "Completed")
    )

    suspend fun getRestaurants(): List<Restaurant> {
        return try {
            val snapshot = db.collection("restaurants").get().await()
            if (snapshot.isEmpty) {
                // Pre-seed sample restaurants for immediate visibility
                seedInitialRestaurants()
                initialSampleRestaurants
            } else {
                snapshot.documents.mapNotNull { doc ->
                    doc.toObject(Restaurant::class.java)?.copy(id = doc.id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load restaurants from Firestore (${e.message}), returning initial dataset")
            initialSampleRestaurants
        }
    }

    suspend fun addRestaurant(restaurant: Restaurant): Result<Unit> {
        return try {
            val docId = if (restaurant.id.isNotEmpty()) restaurant.id else db.collection("restaurants").document().id
            val payload = hashMapOf(
                "id" to docId,
                "name" to restaurant.name,
                "ownerEmail" to restaurant.ownerEmail,
                "phone" to restaurant.phone,
                "address" to restaurant.address,
                "tablesCount" to restaurant.tablesCount,
                "status" to restaurant.status,
                "subscriptionPlan" to restaurant.subscriptionPlan,
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.collection("restaurants").document(docId).set(payload).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add restaurant", e)
            Result.failure(e)
        }
    }

    suspend fun saveBrandRegistration(brand: BrandRegistration): Result<Unit> {
        return try {
            val docId = db.collection("brand_registrations").document().id
            val payload = hashMapOf(
                "brandName" to brand.brandName,
                "ownerName" to brand.ownerName,
                "legalEntity" to brand.legalEntity,
                "gstNumber" to brand.gstNumber,
                "email" to brand.email,
                "phone" to brand.phone,
                "address" to brand.address,
                "subscriptionPlan" to brand.subscriptionPlan,
                "registeredAt" to FieldValue.serverTimestamp(),
                "registeredBy" to requireUserId()
            )
            db.collection("brand_registrations").document(docId).set(payload).await()

            // Also ensure associated restaurant is created/registered
            val restaurantPayload = hashMapOf(
                "id" to docId,
                "name" to brand.brandName,
                "ownerEmail" to brand.email,
                "phone" to brand.phone,
                "address" to brand.address,
                "tablesCount" to 12,
                "status" to "active",
                "subscriptionPlan" to brand.subscriptionPlan,
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.collection("restaurants").document(docId).set(restaurantPayload).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save brand registration", e)
            Result.failure(e)
        }
    }

    suspend fun getOrders(): List<Order> {
        return try {
            val snapshot = db.collection("orders").get().await()
            if (snapshot.isEmpty) {
                seedInitialOrders()
                initialSampleOrders
            } else {
                snapshot.documents.mapNotNull { doc ->
                    doc.toObject(Order::class.java)?.copy(id = doc.id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load orders from Firestore (${e.message}), returning initial dataset")
            initialSampleOrders
        }
    }

    private suspend fun seedInitialRestaurants() {
        try {
            for (r in initialSampleRestaurants) {
                db.collection("restaurants").document(r.id).set(
                    mapOf(
                        "id" to r.id,
                        "name" to r.name,
                        "ownerEmail" to r.ownerEmail,
                        "phone" to r.phone,
                        "address" to r.address,
                        "tablesCount" to r.tablesCount,
                        "status" to r.status,
                        "subscriptionPlan" to r.subscriptionPlan,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not seed restaurants: ${e.message}")
        }
    }

    private suspend fun seedInitialOrders() {
        try {
            for (o in initialSampleOrders) {
                db.collection("orders").document(o.id).set(
                    mapOf(
                        "id" to o.id,
                        "restaurantId" to o.restaurantId,
                        "tableNumber" to o.tableNumber,
                        "itemsSummary" to o.itemsSummary,
                        "totalAmount" to o.totalAmount,
                        "status" to o.status,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not seed orders: ${e.message}")
        }
    }
}
