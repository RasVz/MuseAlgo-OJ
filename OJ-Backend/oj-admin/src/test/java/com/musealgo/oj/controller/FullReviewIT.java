package com.musealgo.oj.controller;

import com.musealgo.oj.judge.entity.*;
import com.musealgo.oj.judge.mapper.SubmissionMapper;
import com.musealgo.oj.judge.worker.JudgeCompletionService;
import com.musealgo.oj.judge.worker.OutboxPublisher;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"oj.judge.worker.enabled=false", "oj.judge.watchdog.enabled=false",
 "oj.messaging.rabbitmq-enabled=false", "oj.messaging.outbox.poll-interval-ms=86400000", "oj.messaging.outbox.initial-delay-ms=86400000", "report.worker.enabled=false", "report.notification.worker-enabled=false",
 "report.retention-cleanup-enabled=false", "oj.contest.settlement-initial-delay-ms=86400000"})
class FullReviewIT extends SyntheticReviewDatabase {
 final List<Long> ownUsers=new ArrayList<>(), ownOperations=new ArrayList<>(), ownContests=new ArrayList<>();
 @Autowired JdbcTemplate jdbc;
 @Autowired JudgeCompletionService completion;
 @Autowired SubmissionMapper submissions;
 @Autowired ObjectProvider<OutboxPublisher> outbox;
 long user() {
  String name="fr_"+UUID.randomUUID().toString().replace("-", "");
  jdbc.update("INSERT INTO user(username,password,nickname,rating) VALUES (?,'synthetic',?,1200)", name,name);
  long id=jdbc.queryForObject("SELECT id FROM user WHERE username=?",Long.class,name);ownUsers.add(id);return id;
 }
 long submission(long user, int status) {
  jdbc.update("INSERT INTO submission(user_id,problem_id,language,status,judge_phase,create_time) VALUES (?,1,'cpp',?,'FINISHED',NOW())",user,status);
  return jdbc.queryForObject("SELECT MAX(id) FROM submission WHERE user_id=?",Long.class,user);
 }
 @Test void concurrentRejudgeCompletionsCountBothItems() throws Exception {
  long operation=910000L+ThreadLocalRandom.current().nextInt(10000);
  jdbc.update("INSERT INTO rejudge_operation(id,operator_id,reason,status,total_items) VALUES (?,1,'synthetic','RUNNING',2)",operation);
  ownOperations.add(operation);
  var tasks=new ArrayList<JudgeTask>();
  for(int i=0;i<2;i++) {
   long user=user(); long id=submission(user,2);
   jdbc.update("INSERT INTO judge_task(submission_id,status,attempt_count,generation,scheduled_at) VALUES (?,'PROCESSING',1,1,NOW())",id);
   long taskId=jdbc.queryForObject("SELECT id FROM judge_task WHERE submission_id=?",Long.class,id);
   jdbc.update("INSERT INTO rejudge_item(operation_id,submission_id,problem_id,user_id,status) VALUES (?,?,1,?,'RUNNING')",operation,id,user);
   var task=new JudgeTask();task.setId(taskId);task.setSubmissionId(id);task.setGeneration(1);tasks.add(task);
  }
  jdbc.execute("CREATE TRIGGER fr_operation_slow BEFORE UPDATE ON rejudge_operation FOR EACH ROW BEGIN IF OLD.id="+operation+" THEN DO SLEEP(0.25); END IF; END");
  var pool=Executors.newFixedThreadPool(2); var gate=new CountDownLatch(1);
  try {
   var futures=new ArrayList<Future<?>>();
   for(var task:tasks) futures.add(pool.submit(() -> {
    try { gate.await(); } catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
    var result=new Submission();result.setId(task.getSubmissionId());result.setStatus((byte)2);result.setJudgePhase("FINISHED");result.setScore(0);
    completion.completeWithOutbox(task,result,false);
   }));
   gate.countDown();for(var future:futures)future.get(20,TimeUnit.SECONDS);
   assertEquals(2,jdbc.queryForObject("SELECT succeeded_items FROM rejudge_operation WHERE id=?",Integer.class,operation));
   assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM rejudge_operation WHERE id=?",String.class,operation));
   assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM rejudge_item WHERE operation_id=? AND status='SUCCEEDED'",Integer.class,operation));
  } finally {pool.shutdownNow();jdbc.execute("DROP TRIGGER IF EXISTS fr_operation_slow");}
 }
 @Test void withoutRabbitDuplicateOutboxDeliveryRebuildsRatingOnce() {
  OutboxPublisher publisher=outbox.getIfAvailable();assertNotNull(publisher,"durable local effects must also run without RabbitMQ");
  long user=user();long id=submission(user,1);
  for(int attempt=0;attempt<2;attempt++) {
   String event=UUID.randomUUID().toString();
   String payload="{\"submissionId\":"+id+",\"taskId\":1,\"judgedAt\":\"synthetic\"}";
   jdbc.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload,status,retry_count,next_attempt_at,created_at) VALUES (?,'SUBMISSION',?,'submission.judged',?,'PENDING',0,NOW(),NOW())",event,id,payload);
   publisher.publishPending();
   assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,event));
  }
  var counts=jdbc.queryForList("SELECT problem_count FROM user_tag_rating WHERE user_id=?",Integer.class,user);
  assertFalse(counts.isEmpty());assertTrue(counts.stream().allMatch(count -> count==1));
 }
 @Autowired com.musealgo.oj.api.user.UserRatingCommand ratings;
 @Autowired com.musealgo.oj.community.favorite.FavoriteApplicationService favorites;
 @Test void ratingBatchRollsBackEarlierUserWhenLaterUserDoesNotExist() {
  long user=user();var updates=new LinkedHashMap<Long,Integer>();updates.put(user,1600);updates.put(Long.MAX_VALUE,1700);
  assertThrows(IllegalStateException.class,()->ratings.batchUpdateRatings(updates));
  assertEquals(1200,jdbc.queryForObject("SELECT rating FROM user WHERE id=?",Integer.class,user));
 }
 @Test void favoriteCounterFailureRollsBackTheRelationAndConcurrentRemovalsCountOnce() throws Exception {
  long user=user();
  jdbc.update("INSERT INTO problem_list(title,type,creator_id,problem_count,favorite_count,privacy) VALUES ('synthetic favorite',1,?,0,1,1)",user);
  long list=jdbc.queryForObject("SELECT MAX(id) FROM problem_list WHERE creator_id=?",Long.class,user);
  jdbc.update("INSERT INTO user_favorite(user_id,target_id,target_type,status) VALUES (?,?,2,1)",user,list);
  var command=new com.musealgo.oj.community.favorite.FavoriteCommand(user,list,com.musealgo.oj.community.favorite.FavoriteTargetType.PROBLEM_LIST);
  jdbc.execute("CREATE TRIGGER fr_favorite_count BEFORE UPDATE ON problem_list FOR EACH ROW BEGIN IF OLD.id="+list+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic favorite failure'; END IF; END");
  try {assertThrows(RuntimeException.class,()->favorites.deactivate(command));
   assertEquals(1,jdbc.queryForObject("SELECT status FROM user_favorite WHERE user_id=? AND target_id=? AND target_type=2",Integer.class,user,list));
   assertEquals(1,jdbc.queryForObject("SELECT favorite_count FROM problem_list WHERE id=?",Integer.class,list));
  } finally {jdbc.execute("DROP TRIGGER IF EXISTS fr_favorite_count");}
  var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
  try {Callable<Boolean> call=()->{gate.await();return favorites.deactivate(command);};
   var a=pool.submit(call);var b=pool.submit(call);gate.countDown();assertFalse(a.get(10,TimeUnit.SECONDS));assertFalse(b.get(10,TimeUnit.SECONDS));
   assertEquals(0,jdbc.queryForObject("SELECT favorite_count FROM problem_list WHERE id=?",Integer.class,list));
  } finally {pool.shutdownNow();}
 }
 @Test void failedTagProjectionRollsBackAllTagsAndDurableRetryRecovers() {
  long user=user();long id=submission(user,1);
  var publisher=outbox.getObject();String event=UUID.randomUUID().toString();
  String payload="{\"submissionId\":"+id+",\"taskId\":1,\"judgedAt\":\"synthetic\"}";
  jdbc.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload,status,retry_count,next_attempt_at,created_at) VALUES (?,'SUBMISSION',?,'submission.judged',?,'PENDING',0,NOW(),NOW())",event,id,payload);
  long failingTag=jdbc.queryForObject("SELECT MAX(tag_id) FROM problem_tag WHERE problem_id=1",Long.class);
  jdbc.execute("CREATE TRIGGER fr_tag_failure BEFORE INSERT ON user_tag_rating FOR EACH ROW BEGIN IF NEW.user_id="+user+" AND NEW.tag_id="+failingTag+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic tag failure'; END IF; END");
  try {publisher.publishPending();
   assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_tag_rating WHERE user_id=?",Integer.class,user));
   assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,event));
   assertEquals(1,jdbc.queryForObject("SELECT retry_count FROM outbox_event WHERE id=?",Integer.class,event));
  } finally {jdbc.execute("DROP TRIGGER IF EXISTS fr_tag_failure");}
  jdbc.update("UPDATE outbox_event SET next_attempt_at=NOW() WHERE id=?",event);publisher.publishPending();
  assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,event));
  var counts=jdbc.queryForList("SELECT problem_count FROM user_tag_rating WHERE user_id=?",Integer.class,user);
  assertFalse(counts.isEmpty());assertTrue(counts.stream().allMatch(count->count==1));
 }

 @Autowired com.musealgo.oj.contest.service.ContestUserService registrations;
 @Test void concurrentRegistrationKeepsOneRowOneCounterAndKnownConflict() throws Exception {
  long user=user();
  jdbc.update("INSERT INTO contest(title,start_time,end_time,duration,visible,participant_count) VALUES ('synthetic concurrent registration',NOW()+INTERVAL 1 DAY,NOW()+INTERVAL 2 DAY,60,1,0)");
  long contest=jdbc.queryForObject("SELECT MAX(id) FROM contest",Long.class);ownContests.add(contest);
  var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
  try {Callable<String> call=()->{gate.await();try{registrations.registerContest(contest,user);return "SUCCESS";}
   catch(com.musealgo.oj.common.exception.BusinessException e){return e.getErrorCode().key();}};
   var a=pool.submit(call);var b=pool.submit(call);gate.countDown();
   assertEquals(java.util.Set.of("SUCCESS","CONTEST_ALREADY_REGISTERED"),java.util.Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contest_user WHERE contest_id=?",Integer.class,contest));
   assertEquals(1,jdbc.queryForObject("SELECT participant_count FROM contest WHERE id=?",Integer.class,contest));
  }finally{pool.shutdownNow();}
 }

 @Autowired com.musealgo.oj.judge.worker.OutboxArchiveTask archive;
 @Test void publishedLocalEventsAreArchivedWithoutRabbit() {
  long user=user();long id=submission(user,2);String event=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload,status,retry_count,next_attempt_at,published_at,created_at) VALUES (?,'SUBMISSION',?,'submission.judged','{}','PUBLISHED',0,NOW(),NOW()-INTERVAL 8 DAY,NOW()-INTERVAL 9 DAY)",event,id);
  archive.archiveExpiredEvents();
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE id=?",Integer.class,event));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event_archive WHERE id=?",Integer.class,event));
 }

 @Autowired com.musealgo.oj.community.service.LikeService likes;
 @Autowired com.musealgo.oj.common.task.LikeSyncItemProcessor likeSync;
 @Autowired org.springframework.data.redis.core.RedisTemplate<String,Object> redis;
 @Autowired org.springframework.data.redis.core.StringRedisTemplate strings;
 @Autowired com.musealgo.oj.teaching.service.ClassService classes;
 @Test void coldCommentLikesRestorePersistedUsersBeforeApplyingNewChanges() {
  long first=user(),second=user();
  jdbc.update("INSERT INTO comment(user_id,entity_id,entity_type,content,like_count) VALUES (?,1,2,'synthetic cold like',1)",first);
  long comment=jdbc.queryForObject("SELECT MAX(id) FROM comment WHERE user_id=?",Long.class,first);
  jdbc.update("INSERT INTO comment_like(user_id,comment_id) VALUES (?,?)",first,comment);
  try {
   redis.delete("comment:like:users:"+comment);
   assertEquals(1,likes.getLikeCount(comment));assertTrue(likes.isLiked(comment,first));
   assertTrue(likes.activate(comment,second));likeSync.syncOne(comment);
   assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM comment_like WHERE comment_id=?",Integer.class,comment));
   assertTrue(likes.deactivate(comment,first));likeSync.syncOne(comment);
   assertEquals(List.of(second),jdbc.queryForList("SELECT user_id FROM comment_like WHERE comment_id=?",Long.class,comment));
  }finally {redis.delete("comment:like:users:"+comment);redis.opsForSet().remove("comment:like:dirty_ids",comment);}
 }
 @Test void simultaneousFirstFavoritesAndTogglesKeepRelationAndCounterConsistent() throws Exception {
  long user=user();jdbc.update("INSERT INTO problem_list(title,type,creator_id,favorite_count,privacy) VALUES ('synthetic first favorite',1,?,0,1)",user);
  long list=jdbc.queryForObject("SELECT MAX(id) FROM problem_list WHERE creator_id=?",Long.class,user);
  var command=new com.musealgo.oj.community.favorite.FavoriteCommand(user,list,com.musealgo.oj.community.favorite.FavoriteTargetType.PROBLEM_LIST);
  concurrent(()->favorites.activate(command));
  assertEquals(1,jdbc.queryForObject("SELECT favorite_count FROM problem_list WHERE id=?",Integer.class,list));
  concurrent(()->favorites.toggle(command));
  assertEquals(1,jdbc.queryForObject("SELECT favorite_count FROM problem_list WHERE id=?",Integer.class,list));
  assertEquals(1,jdbc.queryForObject("SELECT status FROM user_favorite WHERE user_id=? AND target_id=? AND target_type=2",Integer.class,user,list));
 }
 @Autowired com.musealgo.oj.community.service.PostRankingService ranking;
 @Test void hotRankingPrunesDeletedPostsAndOldCategoriesBeforePagination() {
  long user=user();jdbc.update("INSERT INTO post(author_id,title,content,type,category,privacy,is_deleted) VALUES (?,'synthetic stale rank','synthetic',0,'new-category',0,0)",user);
  long id=jdbc.queryForObject("SELECT MAX(id) FROM post WHERE author_id=?",Long.class,user);
  String old=com.musealgo.oj.common.utils.PostRedisKey.hotCategory("old-category");
  String global=com.musealgo.oj.common.utils.PostRedisKey.hotGlobal();
  strings.opsForZSet().add(old,String.valueOf(id),100000);strings.opsForZSet().add(global,String.valueOf(id),100000);
  try {
   assertFalse(ranking.getHotPosts("old-category",1,50).contains(id));assertEquals(0,ranking.getHotPostTotal("old-category"));
   jdbc.update("UPDATE post SET is_deleted=1 WHERE id=?",id);
   assertFalse(ranking.getHotPosts("all",1,50).contains(id));
   assertNull(strings.opsForZSet().score(global,String.valueOf(id)));
  }finally {strings.opsForZSet().remove(old,String.valueOf(id));strings.opsForZSet().remove(global,String.valueOf(id));}
 }
 @Test void reportCanBeFiledAndResolvedAgainAfterEarlierResolution() {
  long user=user();long post=Long.MAX_VALUE-10;
  jdbc.update("INSERT INTO content_report(reporter_id,post_id,reason,status) VALUES (?,?,'synthetic','RESOLVED')",user,post);
  jdbc.update("INSERT INTO content_report(reporter_id,post_id,reason,status) VALUES (?,?,'synthetic','PENDING')",user,post);
  assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update("INSERT INTO content_report(reporter_id,post_id,reason,status) VALUES (?,?,'synthetic duplicate','PENDING')",user,post));
  assertDoesNotThrow(()->jdbc.update("UPDATE content_report SET status='RESOLVED' WHERE reporter_id=? AND post_id=? AND status='PENDING'",user,post));
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM content_report WHERE reporter_id=? AND status='RESOLVED'",Integer.class,user));
 }
 @Test void simultaneousPostTogglesSerializeBeforeChoosingTheirNewState() throws Exception {
  long user=user();jdbc.update("INSERT INTO post(author_id,title,content,type,category,privacy,favorites) VALUES (?,'synthetic post toggle','synthetic',0,'question',0,0)",user);
  long id=jdbc.queryForObject("SELECT MAX(id) FROM post WHERE author_id=?",Long.class,user);
  var command=new com.musealgo.oj.community.favorite.FavoriteCommand(user,id,com.musealgo.oj.community.favorite.FavoriteTargetType.POST);
  jdbc.execute("CREATE TRIGGER fr_post_toggle BEFORE INSERT ON user_favorite FOR EACH ROW BEGIN IF NEW.target_id="+id+" AND NEW.target_type=4 THEN DO SLEEP(0.25); END IF; END");
  try {concurrent(()->favorites.toggle(command));
   assertEquals(0,jdbc.queryForObject("SELECT status FROM user_favorite WHERE user_id=? AND target_id=? AND target_type=4",Integer.class,user,id));
   assertEquals(0,jdbc.queryForObject("SELECT favorites FROM post WHERE id=?",Integer.class,id));
  }finally {jdbc.execute("DROP TRIGGER IF EXISTS fr_post_toggle");clearPostCache(id);}
 }
 void clearPostCache(long id) {
  strings.opsForZSet().remove(com.musealgo.oj.common.utils.PostRedisKey.hotGlobal(),String.valueOf(id));
  strings.opsForZSet().remove(com.musealgo.oj.common.utils.PostRedisKey.hotCategory("question"),String.valueOf(id));
  strings.delete(List.of(com.musealgo.oj.common.utils.PostRedisKey.stat(id),com.musealgo.oj.common.utils.PostRedisKey.favedUsers(id),com.musealgo.oj.common.utils.PostRedisKey.hotScore(id)));
 }
 @Test void postFavoriteNotificationFailureRollsBackWithoutChangingRedisFavoriteState() {
  long author=user(),actor=user();jdbc.update("INSERT INTO post(author_id,title,content,type,category,privacy,favorites) VALUES (?,'synthetic notification rollback','synthetic',0,'question',0,0)",author);
  long id=jdbc.queryForObject("SELECT MAX(id) FROM post WHERE author_id=?",Long.class,author);
  jdbc.execute("CREATE TRIGGER fr_post_notification BEFORE INSERT ON user_notification FOR EACH ROW BEGIN IF NEW.user_id="+author+" AND NEW.type='FAVORITE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic notification failure'; END IF; END");
  try {
   var command=new com.musealgo.oj.community.favorite.FavoriteCommand(actor,id,com.musealgo.oj.community.favorite.FavoriteTargetType.POST);
   assertThrows(RuntimeException.class,()->favorites.activate(command));
   assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite WHERE user_id=? AND target_id=? AND target_type=4",Integer.class,actor,id));
   assertEquals(0,jdbc.queryForObject("SELECT favorites FROM post WHERE id=?",Integer.class,id));
   assertFalse(Boolean.TRUE.equals(strings.opsForSet().isMember(com.musealgo.oj.common.utils.PostRedisKey.favedUsers(id),String.valueOf(actor))));
  }finally{jdbc.execute("DROP TRIGGER IF EXISTS fr_post_notification");clearPostCache(id);}
 }
 @Autowired com.musealgo.oj.judge.service.SubmissionService receipts;
 @Test void normalSubmissionRollsBackSourceTaskAndUserCounterOnEitherWriteFailure() {
  long user=user();
  for(String table:List.of("submission_code","judge_task")) {
   jdbc.execute("CREATE TRIGGER fr_receipt BEFORE INSERT ON "+table+" FOR EACH ROW BEGIN IF EXISTS(SELECT 1 FROM submission WHERE id=NEW.submission_id AND user_id="+user+") THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic receipt failure'; END IF; END");
   try {
    var receipt=new Submission();receipt.setUserId(user);receipt.setProblemId(1L);receipt.setLanguage("cpp");receipt.setCode("// synthetic");
    assertThrows(RuntimeException.class,()->receipts.submitCode(receipt));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM submission WHERE user_id=?",Integer.class,user));
    assertEquals(0,jdbc.queryForObject("SELECT submit_count FROM user WHERE id=?",Integer.class,user));
   }finally{jdbc.execute("DROP TRIGGER IF EXISTS fr_receipt");}
  }
  var receipt=new Submission();receipt.setUserId(user);receipt.setProblemId(1L);receipt.setLanguage("cpp");receipt.setCode("// synthetic success");
  long id=receipts.submitCode(receipt).getId();
  assertEquals("// synthetic success",jdbc.queryForObject("SELECT code FROM submission_code WHERE submission_id=?",String.class,id));
  assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM judge_task WHERE submission_id=?",String.class,id));
  assertEquals(1,jdbc.queryForObject("SELECT submit_count FROM user WHERE id=?",Integer.class,user));
 }
 @Autowired com.musealgo.oj.teaching.application.ClassMembershipApplicationService memberships;
 @Test void classMembershipAndCounterRollBackTogetherOnJoinAndRemoval() {
  long user=user();jdbc.update("INSERT INTO class(name,teacher_id) VALUES ('synthetic class rollback',?)",user);
  long id=jdbc.queryForObject("SELECT MAX(id) FROM class WHERE teacher_id=?",Long.class,user);
  String key="invite:code:rollback_"+user;strings.opsForValue().set(key,String.valueOf(id),java.time.Duration.ofMinutes(1));
  String trigger="CREATE TRIGGER fr_class_count BEFORE UPDATE ON class FOR EACH ROW BEGIN IF OLD.id="+id+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic class count failure'; END IF; END";
  jdbc.execute(trigger);
  try {assertThrows(RuntimeException.class,()->classes.joinByInviteCode(user,"rollback_"+user));
   assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM class_student WHERE class_id=?",Integer.class,id));
  }finally{jdbc.execute("DROP TRIGGER IF EXISTS fr_class_count");}
  assertEquals(2,classes.joinByInviteCode(user,"rollback_"+user));strings.delete(key);
  var principal=new com.musealgo.oj.system.entity.User();principal.setId(user);
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TEACHER"))));
  try {
   jdbc.execute(trigger);
   try {assertThrows(RuntimeException.class,()->memberships.removeMember(id,user));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM class_student WHERE class_id=?",Integer.class,id));
    assertEquals(1,classes.getById(id).getStudentCount());
   }finally{jdbc.execute("DROP TRIGGER IF EXISTS fr_class_count");}
   memberships.removeMember(id,user);assertEquals(0,classes.getById(id).getStudentCount());
   assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM class_student WHERE class_id=?",Integer.class,id));
  }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();strings.delete(key);}
 }
 @Autowired com.musealgo.oj.community.controller.PostHotController hotController;
 @Test void coldHotSnapshotRestoresOlderCountersAndKeepsWarmRedisChanges() {
  long user=user();String categoryName="fr-cold-"+user;
  jdbc.update("INSERT INTO post(author_id,title,content,type,category,privacy,views,likes,comment_count,favorites,create_time,update_time) VALUES (?,'synthetic older cold post','synthetic',0,?,0,20,5,4,3,NOW()-INTERVAL 9 DAY,NOW()-INTERVAL 8 DAY)",user,categoryName);
  long id=jdbc.queryForObject("SELECT MAX(id) FROM post WHERE author_id=?",Long.class,user);
  String stat=com.musealgo.oj.common.utils.PostRedisKey.stat(id),category=com.musealgo.oj.common.utils.PostRedisKey.hotCategory(categoryName);
  strings.delete(stat);var query=new com.musealgo.oj.community.entity.dto.PostQueryDTO();query.setCategory(categoryName);
  try {
   var cold=(com.musealgo.oj.community.entity.vo.PostHotVO)((List<?>)hotController.getHotPosts(query).getData().get("list")).get(0);
   assertEquals(id,cold.getPostId());assertEquals(20,cold.getViews());assertEquals(5,cold.getLikes());assertEquals(4,cold.getComments());
   strings.opsForHash().put(stat,"likes","9");strings.opsForHash().put(stat,"views","99");
   var warm=(com.musealgo.oj.community.entity.vo.PostHotVO)((List<?>)hotController.getHotPosts(query).getData().get("list")).get(0);
   assertEquals(9,warm.getLikes());assertEquals(99,warm.getViews());
  }finally {strings.delete(List.of(stat,com.musealgo.oj.common.utils.PostRedisKey.hotScore(id)));strings.opsForZSet().remove(category,String.valueOf(id));strings.opsForZSet().remove(com.musealgo.oj.common.utils.PostRedisKey.hotGlobal(),String.valueOf(id));}
 }
 @Autowired AdminClassController adminClasses;
 @Test void classListCountsExistingMembershipsEvenWhenHistoricalCounterIsStale() {
  long user=user();jdbc.update("INSERT INTO class(name,teacher_id,student_count) VALUES ('synthetic stale count',?,0)",user);
  long id=jdbc.queryForObject("SELECT MAX(id) FROM class WHERE teacher_id=?",Long.class,user);
  jdbc.update("INSERT INTO class_student(class_id,student_id) VALUES (?,?)",id,user);
  var principal=new com.musealgo.oj.system.entity.User();principal.setId(user);
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
  try {
   var record=adminClasses.list(1,20,"synthetic stale count","all","newest").getData().getRecords().stream().filter(row->row.getId()==id).findFirst().orElseThrow();
   assertEquals(1,record.getStudentCount().intValue());
  }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 }

 @Test void adminClassDirectoryAppliesMemberFiltersSearchAndStableSortsInSql() {
  long teacher=user();
  String marker="directory_"+UUID.randomUUID().toString().replace("-", "");
  jdbc.update("INSERT INTO `class`(name,teacher_id,school,create_time,student_count) VALUES (?,?,?,?,'0')",
          marker+"_A",teacher,marker+"_school",java.sql.Timestamp.valueOf("2026-01-01 10:00:00"));
  long firstId=jdbc.queryForObject("SELECT MAX(id) FROM `class` WHERE teacher_id=?",Long.class,teacher);
  jdbc.update("INSERT INTO `class`(name,teacher_id,school,create_time,student_count) VALUES (?,?,?,?,'0')",
          marker+"_B",teacher,marker+"_school",java.sql.Timestamp.valueOf("2026-01-01 10:00:00"));
  long secondId=jdbc.queryForObject("SELECT MAX(id) FROM `class` WHERE teacher_id=?",Long.class,teacher);
  jdbc.update("INSERT INTO class_student(class_id,student_id) VALUES (?,?)",firstId,teacher);
  jdbc.update("INSERT INTO `class`(name,teacher_id,is_deleted) VALUES (?,?,1)",marker+"_deleted",teacher);
  var principal=new com.musealgo.oj.system.entity.User();principal.setId(teacher);
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
          new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,
                  List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
  try {
   var withMembers=adminClasses.list(1,20,marker,"hasMembers","newest").getData();
   assertEquals(List.of(firstId),withMembers.getRecords().stream().map(com.musealgo.oj.dto.AdminClassSummary::getId).toList());
   assertEquals(1L,withMembers.getRecords().get(0).getStudentCount());
   var empty=adminClasses.list(1,20,marker,"empty","newest").getData();
   assertEquals(List.of(secondId),empty.getRecords().stream().map(com.musealgo.oj.dto.AdminClassSummary::getId).toList());
   var newest=adminClasses.list(1,20,marker,"all","newest").getData();
   assertEquals(List.of(secondId,firstId),newest.getRecords().stream().map(com.musealgo.oj.dto.AdminClassSummary::getId).toList());
   var oldest=adminClasses.list(1,20,marker,"all","oldest").getData();
   assertEquals(List.of(firstId,secondId),oldest.getRecords().stream().map(com.musealgo.oj.dto.AdminClassSummary::getId).toList());
   var byName=adminClasses.list(1,20,marker,"all","name").getData();
   assertEquals(List.of(firstId,secondId),byName.getRecords().stream().map(com.musealgo.oj.dto.AdminClassSummary::getId).toList());
   assertEquals(secondId,adminClasses.list(1,20,String.valueOf(secondId),"all","newest")
           .getData().getRecords().get(0).getId());
  }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 }

 @Test void adminClassDirectorySearchesActiveTeachersAndHidesDeletedTeacherNames() {
  long activeTeacher=user();
  String activeName="active_directory_teacher_"+UUID.randomUUID().toString().replace("-", "");
  jdbc.update("UPDATE user SET username=?,nickname=? WHERE id=?",activeName,activeName,activeTeacher);
  jdbc.update("INSERT INTO `class`(name,teacher_id) VALUES ('neutral active class',?)",activeTeacher);
  long activeClassId=jdbc.queryForObject("SELECT MAX(id) FROM `class` WHERE teacher_id=?",Long.class,activeTeacher);

  long deletedTeacher=user();
  String deletedName="deleted_directory_teacher_"+UUID.randomUUID().toString().replace("-", "");
  jdbc.update("UPDATE user SET username=?,nickname=?,is_deleted=1 WHERE id=?",deletedName,deletedName,deletedTeacher);
  jdbc.update("INSERT INTO `class`(name,teacher_id) VALUES ('neutral deleted class',?)",deletedTeacher);
  long deletedClassId=jdbc.queryForObject("SELECT MAX(id) FROM `class` WHERE teacher_id=?",Long.class,deletedTeacher);

  var principal=new com.musealgo.oj.system.entity.User();principal.setId(activeTeacher);
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
          new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,
                  List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
  try {
   var activeMatches=adminClasses.list(1,20,activeName,"all","newest").getData();
   assertEquals(List.of(activeClassId),activeMatches.getRecords().stream()
           .map(com.musealgo.oj.dto.AdminClassSummary::getId).toList());
   var deletedMatches=adminClasses.list(1,20,deletedName,"all","newest").getData();
   assertEquals(0L,deletedMatches.getTotal());
   var deletedClass=adminClasses.list(1,20,String.valueOf(deletedClassId),"all","newest")
           .getData().getRecords().get(0);
   assertNull(deletedClass.getTeacherName());
  }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 }

 @Test void adminClassDirectoryRequiresAdministratorRole() {
  var principal=new com.musealgo.oj.system.entity.User();principal.setId(987654321L);
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
          new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,
                  List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TEACHER"))));
  try {
   assertThrows(org.springframework.security.access.AccessDeniedException.class,
           ()->adminClasses.list(1,20,null,"all","newest"));
  }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 }
 @Test void concurrentClassJoinIsIdempotentAndDoesNotBecomeInternalError() throws Exception {
  long user=user();jdbc.update("INSERT INTO class(name,teacher_id) VALUES ('synthetic join',?)",user);
  long id=jdbc.queryForObject("SELECT MAX(id) FROM class WHERE teacher_id=?",Long.class,user);
  String code="fr_"+user;strings.opsForValue().set("invite:code:"+code,String.valueOf(id),java.time.Duration.ofMinutes(1));
  try {concurrent(()->classes.joinByInviteCode(user,code));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM class_student WHERE class_id=?",Integer.class,id));
   assertEquals(1,classes.getById(id).getStudentCount());
  }finally {strings.delete("invite:code:"+code);}
 }
 void concurrent(Callable<?> call) throws Exception {
  var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
  try {Callable<Object> run=()->{gate.await();return call.call();};var a=pool.submit(run);var b=pool.submit(run);gate.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);}
  finally {pool.shutdownNow();}
 }

 @org.junit.jupiter.api.AfterEach void cleanOwnedRows() {
  for(Long user:ownUsers) {
   jdbc.update("DELETE FROM user_notification WHERE user_id=? OR actor_id=?",user,user);
   jdbc.update("DELETE FROM content_report WHERE reporter_id=?",user);
   jdbc.update("DELETE FROM comment_like WHERE user_id=? OR comment_id IN (SELECT id FROM comment WHERE user_id=?)",user,user);
   jdbc.update("DELETE FROM comment WHERE user_id=?",user);
   jdbc.update("DELETE FROM post WHERE author_id=?",user);
   jdbc.update("DELETE FROM class_student WHERE student_id=? OR class_id IN (SELECT id FROM class WHERE teacher_id=?)",user,user);
   jdbc.update("DELETE FROM class WHERE teacher_id=?",user);
   jdbc.update("DELETE FROM outbox_event_archive WHERE aggregate_type='SUBMISSION' AND aggregate_id IN (SELECT id FROM submission WHERE user_id=?)",user);
   jdbc.update("DELETE FROM outbox_event WHERE aggregate_type='SUBMISSION' AND aggregate_id IN (SELECT id FROM submission WHERE user_id=?)",user);
   jdbc.update("DELETE FROM rejudge_item WHERE user_id=?",user);
   jdbc.update("DELETE FROM judge_task WHERE submission_id IN (SELECT id FROM submission WHERE user_id=?)",user);
   jdbc.update("DELETE FROM submission_result WHERE submission_id IN (SELECT id FROM submission WHERE user_id=?)",user);
   jdbc.update("DELETE FROM submission_code WHERE submission_id IN (SELECT id FROM submission WHERE user_id=?)",user);
   jdbc.update("DELETE FROM submission WHERE user_id=?",user);
   jdbc.update("DELETE FROM user_favorite WHERE user_id=?",user);
   jdbc.update("DELETE FROM problem_list WHERE creator_id=?",user);
   jdbc.update("DELETE FROM contest_user WHERE user_id=?",user);
   jdbc.update("DELETE FROM user_tag_rating WHERE user_id=?",user);
   jdbc.update("DELETE FROM user_tag_rating_lock WHERE user_id=?",user);
   jdbc.update("DELETE FROM user WHERE id=?",user);
  }
  for(Long id:ownOperations)jdbc.update("DELETE FROM rejudge_operation WHERE id=?",id);
  for(Long id:ownContests)jdbc.update("DELETE FROM contest WHERE id=?",id);
 }
 @Test void concurrentDuplicateDeliveriesSerializeTheSameUsersProjection() throws Exception {
  long user=user();var publisher=outbox.getObject();
  for(int i=0;i<2;i++) {
   long id=submission(user,1);String payload="{\"submissionId\":"+id+",\"taskId\":1,\"judgedAt\":\"synthetic\"}";
   jdbc.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload,status,retry_count,next_attempt_at,created_at) VALUES (?,'SUBMISSION',?,'submission.judged',?,'PENDING',0,NOW(),NOW())",UUID.randomUUID().toString(),id,payload);
  }
  var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
  try {Callable<Void> call=()->{gate.await();publisher.publishPending();return null;};
   var a=pool.submit(call);var b=pool.submit(call);gate.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
   var counts=jdbc.queryForList("SELECT problem_count FROM user_tag_rating WHERE user_id=?",Integer.class,user);
   assertFalse(counts.isEmpty());assertTrue(counts.stream().allMatch(count->count==2));
  }finally{pool.shutdownNow();}
 }

}


/** Refuse application-context startup against implicit or non-synthetic datasource defaults. */
abstract class SyntheticReviewDatabase {
 @org.springframework.test.context.DynamicPropertySource
 static void requireIsolatedDatabase(org.springframework.test.context.DynamicPropertyRegistry properties) {
  String port=System.getProperty("musealgo.review.expected-db-port");
  String url=System.getenv("SPRING_DATASOURCE_URL");
  if(!Boolean.getBoolean("musealgo.review.synthetic") || port==null || url==null
    || !url.startsWith("jdbc:mysql://127.0.0.1:"+port+"/oj_db?"))
   throw new IllegalStateException("Full-review integration tests require the disposable synthetic runner");
  properties.add("spring.datasource.url",()->url);
 }
}
