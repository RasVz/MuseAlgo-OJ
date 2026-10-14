<template>
  <div class="home-dashboard">
    <NavbarContext>
      <div class="home-nav-title">
        <span class="welcome-text">欢迎来到 睿思学院</span>
      </div>
    </NavbarContext>

    <el-row :gutter="20" class="main-row">
      <el-col :span="17" class="left-col">
        <div class="section-top">
          <RecentContestCard />
        </div>
        <!-- <div class="section-mid">
          <DailyRecommendationsCard />
        </div> -->
        <div class="section-bot">
          <RecentColumnCard />
        </div>
      </el-col>

      <el-col :span="7" class="right-col">
        <div class="sidebar-top">
          <AnnouncementCard />
        </div>
        <div class="sidebar-bot">
          <AcceptedRankCard />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import AcceptedRankCard from '@/components/AcceptedRankCard.vue'
import AnnouncementCard from '@/components/AnnouncementCard.vue'
import NavbarContext from '@/components/NavbarContext.vue'
import RecentContestCard from '@/components/RecentContestCard.vue'
import RecentColumnCard from '@/components/RecentColumnCard.vue'
</script>

<style scoped>
.home-dashboard {
  height: calc(100vh - 85px - 40px);
  overflow-y: auto;
  overflow-x: hidden;
}

.home-nav-title { 
  font-size: 18px; 
  font-weight: bold; 
  color: var(--text-primary); 
}

.welcome-text { 
  background: linear-gradient(90deg, var(--text-regular), var(--text-secondary)); 
  -webkit-background-clip: text; 
  -webkit-text-fill-color: transparent; 
}

.main-row { 
  height: 100%; 
}

.left-col, .right-col {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 高度分配，或者由内容撑开 */
.section-top { min-height: 180px; }
.section-mid { min-height: 200px; }
.section-bot { flex: 1; min-height: 300px; }

.sidebar-top { min-height: 100px; }
.sidebar-bot { min-height: 200px; }
</style>
