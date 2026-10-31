<template>
  <main class="community-admin admin-scroll-page" v-loading="loading">
    <div class="page-header">
      <div>
        <div class="page-title">社区治理</div>
        <div class="page-desc">处理举报、控制内容可见范围，并维护可供作者选择的扩展标签</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="community-tabs" @tab-change="load">
      <el-tab-pane label="待审核举报" name="reports">
        <el-empty v-if="!reports.length && !loading" description="暂无待处理举报" />
        <el-card v-else shadow="never" class="panel-card">
          <el-table :data="reports" stripe>
            <el-table-column prop="title" label="被举报帖子" min-width="240" show-overflow-tooltip />
            <el-table-column label="举报人" min-width="130">
              <template #default="{ row }">
                <div class="user-cell">
                  <span class="user-name">{{ row.reporterName || '匿名' }}</span>
                  <span v-if="row.reporterUsername" class="user-sub">@{{ row.reporterUsername }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="作者" min-width="130">
              <template #default="{ row }">
                <div class="user-cell">
                  <span class="user-name">{{ row.reportedName || '未知用户' }}</span>
                  <span v-if="row.reportedUsername" class="user-sub">@{{ row.reportedUsername }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center">
              <template #default="{ row }">
                <el-button type="primary" link @click="openReportDetail(row)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="内容运营" name="post">
        <el-card shadow="never" class="panel-card">
          <p class="panel-tip">输入帖子 ID 后，可由管理员调整置顶、精选和可见范围。</p>
          <div class="operator-form">
            <el-input v-model="postId" placeholder="帖子 ID" style="width: 180px" />
            <el-switch v-model="postState.isTop" active-text="置顶" />
            <el-switch v-model="postState.isEssence" active-text="精选" />
            <el-switch v-model="postState.isPublic" active-text="公开" />
            <el-button type="primary" @click="savePostState">保存设置</el-button>
          </div>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="标签管理" name="tags">
        <el-card shadow="never" class="panel-card">
          <div class="tag-add">
            <el-input v-model="tagForm.name" placeholder="新标签名称" style="width: 210px" />
            <el-input v-model="tagForm.groupName" placeholder="分组，例如：自定义专题" style="width: 240px" />
            <el-button type="primary" @click="addTag">添加标签</el-button>
          </div>
          <el-table :data="tags" style="width: 100%" stripe>
            <el-table-column prop="name" label="标签" />
            <el-table-column prop="groupName" label="分组" />
            <el-table-column label="状态" width="170">
              <template #default="{ row }">
                <el-switch :model-value="Boolean(row.enabled)" active-text="启用" inactive-text="停用" @change="setTag(row, $event)" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </main>
</template>

<script setup>
// 社区治理（仅管理员）：举报处理、按帖子 ID 调整置顶/加精/公开状态、标签治理。
//
// 这里的干预手段都是「按 ID 精确操作」而非按列表浏览——治理对象由举报或外部举报渠道给出，
// 管理员不需要（也不应该）看到全站帖子列表。
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createCommunityTag, getCommunityTags, getModerationReports, moderatePost, setCommunityTagEnabled } from '@/api/community'
const router = useRouter(); const loading = ref(false); const tab = ref('reports'); const reports = ref([]); const tags = ref([]); const postId = ref(''); const postState = ref({ isTop:false, isEssence:false, isPublic:true }); const tagForm = ref({ name:'', groupName:'自定义标签' })
const load = async () => { loading.value = true; try { if (tab.value === 'reports') { const res = await getModerationReports(); reports.value = res.code === 200 ? res.data || [] : [] } if (tab.value === 'tags') { const res = await getCommunityTags(); tags.value = res.code === 200 ? res.data || [] : [] } } finally { loading.value = false } }
const openReportDetail = (row) => { const { href } = router.resolve({ name: 'AdminReportDetail', params: { id: row.id } }); window.open(href, '_blank') }
const savePostState = async () => { if (!postId.value) return ElMessage.warning('请输入帖子 ID'); const res = await moderatePost(postId.value, { isTop:postState.value.isTop, isEssence:postState.value.isEssence, privacy:postState.value.isPublic ? 0 : 1 }); if (res.code === 200) ElMessage.success('内容状态已更新') }
const addTag = async () => { if (!tagForm.value.name.trim()) return ElMessage.warning('请输入标签名称'); const res = await createCommunityTag(tagForm.value); if (res.code === 200) { ElMessage.success('标签已添加'); tagForm.value.name=''; load() } }
const setTag = async (tag, enabled) => { const res = await setCommunityTagEnabled(tag.id, enabled); if (res.code === 200) tag.enabled = enabled ? 1 : 0 }
load()
</script>

<style scoped>
.community-admin { padding: 16px; }
.page-header { margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; color: var(--text-primary); }
.page-desc { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
.community-tabs :deep(.el-tabs__header) { margin-bottom: 16px; }
.user-cell { display: flex; flex-direction: column; line-height: 1.4; }
.user-name { font-weight: 600; color: var(--text-primary); }
.user-sub { font-size: 12px; color: var(--text-secondary); }
.panel-card { border: 1px solid var(--border-light); border-radius: 12px; }
.panel-tip { margin: 0 0 16px; color: var(--text-secondary); font-size: 13px; }
.operator-form, .tag-add { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.tag-add { margin-bottom: 16px; }
</style>
