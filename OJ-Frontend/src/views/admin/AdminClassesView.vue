<template>
  <div class="admin-classes">
    <div class="page-header">
      <div>
        <div class="page-title">班级管理</div>
        <div class="page-desc">查看所有教师创建的班级（管理员专属）</div>
      </div>
      <div class="search">
        <el-input
          v-model="keyword"
          placeholder="班级名称 / 学校 / 教师 / ID"
          clearable
          style="width: 280px"
          @keyup.enter="fetchList"
          @clear="fetchList"
        />
        <el-button type="primary" @click="fetchList">搜索</el-button>
      </div>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="classes" border stripe height="100%">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="name" label="班级名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="school" label="所属机构" min-width="140">
          <template #default="{ row }">{{ row.school || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建教师" min-width="130">
          <template #default="{ row }">{{ row.teacherName || `#${row.teacherId}` }}</template>
        </el-table-column>
        <el-table-column prop="studentCount" label="学生数" width="90" align="center" />
        <el-table-column prop="createTime" label="创建时间" min-width="150" />
        <el-table-column label="操作" width="120" align="center">
          <template #default="{ row }">
            <el-button type="primary" link @click="openManage(row)">进入管理</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="fetchList"
      />
    </el-card>
  </div>
</template>

<script setup>
// 全部班级列表（仅管理员）：教师只能看到自己创建的班级，此处是跨教师的全局视角。
// 仅做检索与跳转，班级内的成员/作业/题单等管理在 AdminClassDetail 中完成。
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getAdminClasses } from '@/api/adminClass'
import { unwrapResultData } from '@/utils/response'

const router = useRouter()
const classes = ref([])
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const res = await getAdminClasses({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined
    })
    const data = unwrapResultData(res)
    classes.value = data?.records ?? []
    total.value = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

const openManage = (row) => {
  router.push({ name: 'AdminClassDetail', params: { classId: row.id } })
}

onMounted(fetchList)
</script>

<style scoped>
.admin-classes { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.search { display: flex; gap: 8px; }
.admin-classes :deep(.el-card) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.admin-classes :deep(.el-card__body) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.admin-classes :deep(.el-table) { flex: 1; min-height: 0; }
.pager { margin-top: auto; padding-top: 12px; justify-content: flex-end; }
</style>
