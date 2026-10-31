<template>
  <div class="admin-announcements">
    <div class="page-header">
      <div>
        <div class="page-title">公告管理</div>
        <div class="page-desc">发布、编辑与置顶系统公告</div>
      </div>
      <el-button type="primary" @click="openCreate">发布公告</el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="announcements" border stripe height="100%">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column label="置顶" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isTop === 1 ? 'warning' : 'info'" size="small">{{ row.isTop === 1 ? '置顶' : '普通' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="发布时间" min-width="150" />
        <el-table-column label="操作" width="170" align="center">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
            <el-button type="danger" link @click="onDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑公告' : '发布公告'" width="680px">
      <el-form label-width="70px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="公告标题" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="8" placeholder="支持 Markdown" />
        </el-form-item>
        <el-form-item label="置顶">
          <el-switch v-model="form.isTop" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 公告管理（仅管理员）：发布、编辑、删除全站公告，并可置顶。
// 公告对所有登录用户可见，删除后无法恢复，因此删除走二次确认。
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAnnouncementList, saveAnnouncement, deleteAnnouncement } from '@/api/adminAnnouncement'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'

const announcements = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const dialogVisible = ref(false)
const saving = ref(false)
const form = ref({ id: null, title: '', content: '', isTop: 0 })

const fetchList = async () => {
  loading.value = true
  try {
    const res = await getAnnouncementList({ page: page.value, size: size.value })
    const data = unwrapResultData(res)
    announcements.value = data?.records ?? []
    total.value = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  form.value = { id: null, title: '', content: '', isTop: 0 }
  dialogVisible.value = true
}

const openEdit = (row) => {
  form.value = { id: row.id, title: row.title, content: row.content, isTop: row.isTop ?? 0 }
  dialogVisible.value = true
}

const onSave = async () => {
  if (!form.value.title.trim() || !form.value.content.trim()) {
    ElMessage.warning('标题与内容不能为空')
    return
  }
  saving.value = true
  try {
    const res = await saveAnnouncement(form.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '保存失败'))
      return
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    fetchList()
  } finally {
    saving.value = false
  }
}

const onDelete = async (row) => {
  await ElMessageBox.confirm(`确定删除公告「${row.title}」？`, '删除确认', { type: 'warning' })
  const res = await deleteAnnouncement(row.id)
  if (!isResultSuccess(res)) {
    ElMessage.error(getResultMessage(res, '删除失败'))
    return
  }
  ElMessage.success('已删除')
  fetchList()
}

onMounted(fetchList)
</script>

<style scoped>
.admin-announcements { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.admin-announcements :deep(.el-card) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.admin-announcements :deep(.el-card__body) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.admin-announcements :deep(.el-table) { flex: 1; min-height: 0; }
.admin-announcements .pager { margin-top: auto; padding-top: 12px; }
</style>
