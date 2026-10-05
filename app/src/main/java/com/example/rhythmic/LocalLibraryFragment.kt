package com.example.rhythmic

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class LocalLibraryFragment : Fragment(R.layout.fragment_local_library) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = view.findViewById<ViewPager2>(R.id.viewPager)

        // ViewPager Adaptörü: 1. sıraya Şarkıları, 2. sıraya Listeleri koyar
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment {
                return if (position == 0) AudioFragment() else PlaylistFragment()
            }
        }

        // Sekmelerin isimlerini belirleyip sayfalarla senkronize ediyoruz
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) "Tüm Şarkılar" else "Oynatma Listeleri"
        }.attach()
    }
}