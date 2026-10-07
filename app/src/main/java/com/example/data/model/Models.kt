package com.example.data.model

import com.google.firebase.Timestamp

enum class UserRole {
    superAdmin,
    client,
    operator
}

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val role: String = "client",
    val name: String = "",
    val status: String = "active",
    val brandName: String = "",
    val ownerName: String = "",
    val phone: String = "",
    val createdAt: Timestamp? = null,
    val lastLoginAt: Timestamp? = null,
    val lastPasswordChangedAt: Timestamp? = null
) {
    val isSuperAdmin: Boolean
        get() = role == "superAdmin" && email.equals("team.clicknetmedia@gmail.com", ignoreCase = true)
}

data class Restaurant(
    val id: String = "",
    val name: String = "",
    val ownerEmail: String = "",
    val phone: String = "",
    val address: String = "",
    val tablesCount: Int = 10,
    val status: String = "active", // active, maintenance, closed
    val subscriptionPlan: String = "Enterprise", // Starter, Professional, Enterprise
    val revenueMonthly: Double = 0.0,
    val activeOrdersCount: Int = 0,
    val createdAt: Timestamp? = null
)

data class Order(
    val id: String = "",
    val restaurantId: String = "",
    val tableNumber: String = "",
    val itemsSummary: String = "",
    val totalAmount: Double = 0.0,
    val status: String = "Pending", // Pending, Preparing, Served, Completed, Cancelled
    val createdAt: Timestamp? = null
)

data class BrandRegistration(
    val brandName: String = "",
    val ownerName: String = "",
    val legalEntity: String = "",
    val gstNumber: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val subscriptionPlan: String = "Enterprise"
)
