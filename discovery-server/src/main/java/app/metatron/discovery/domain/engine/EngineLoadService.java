/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.metatron.discovery.domain.engine;

import app.metatron.discovery.common.GlobalObjectMapper;
import app.metatron.discovery.common.ProgressResponse;
import app.metatron.discovery.common.datasource.DataType;
import app.metatron.discovery.common.datasource.LogicalType;
import app.metatron.discovery.common.fileloader.FileLoaderFactory;
import app.metatron.discovery.common.fileloader.FileLoaderProperties;
import app.metatron.discovery.domain.datasource.*;
import app.metatron.discovery.domain.datasource.connection.DataConnection;
import app.metatron.discovery.domain.datasource.connection.jdbc.JdbcConnectionService;
import app.metatron.discovery.domain.datasource.connection.jdbc.JdbcDataConnection;
import app.metatron.discovery.domain.datasource.ingestion.jdbc.LinkIngestionInfo;
import app.metatron.discovery.domain.workbook.configurations.filter.Filter;
import app.metatron.discovery.spec.druid.ingestion.BulkLoadSpec;
import app.metatron.discovery.spec.druid.ingestion.BulkLoadSpecBuilder;
import app.metatron.discovery.util.PolarisUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static app.metatron.discovery.domain.datasource.DataSource.DataSourceType.VOLATILITY;
import static app.metatron.discovery.domain.datasource.DataSourceTemporary.LoadStatus.ENABLE;
import static app.metatron.discovery.domain.datasource.DataSourceTemporary.LoadStatus.FAIL;
import static app.metatron.discovery.domain.datasource.Field.FIELD_NAME_CURRENT_TIMESTAMP;

@Component
public class EngineLoadService {

  private static final Logger LOGGER = LoggerFactory.getLogger(EngineLoadService.class);

  public static final String TOPIC_LOAD_PROGRESS = "/topic/datasources/%s/progress";

  @Autowired
  DruidEngineMetaRepository engineMetaRepository;

  @Autowired
  DruidEngineRepository engineRepository;

  @Autowired
  DataSourceRepository dataSourceRepository;

  @Autowired
  DataSourceTemporaryRepository temporaryRepository;

  @Autowired
  JdbcConnectionService jdbcConnectionService;

  @Autowired
  SimpMessageSendingOperations messagingTemplate;

  @Autowired
  EngineProperties engineProperties;

  @Autowired
  FileLoaderFactory fileLoaderFactory;

  /**
   * 엔진내 BulkLoad Timeout 시간, 기본값 10 분
   */
  @Value("${polaris.engine.timeout.bulk:900}")
  Integer timeout;

  @Value("${polaris.load.maxrow:1000000}")
  Integer maxRow;

  public EngineLoadService() {
  }

  public void sendTopic(String topicUri, ProgressResponse progressResponse) {
    LOGGER.debug("Send Progress Topic : {}, {}", topicUri, progressResponse);
    messagingTemplate.convertAndSend(topicUri,
                                     GlobalObjectMapper.writeValueAsString(progressResponse));

  }

  @Transactional
  public DataSourceTemporary load(DataSource dataSource, List<Filter> filters) {
    return load(dataSource, filters, false, null);
  }

  @Transactional
  public DataSourceTemporary load(DataSource dataSource, List<Filter> filters, boolean async, String temporaryId) {

    if (async) { // delay for subscribe time.
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {}
    }

    String sendTopicUri = String.format(TOPIC_LOAD_PROGRESS, temporaryId);

    boolean isVoatile = false;

    if(StringUtils.isEmpty(dataSource.getId()) && dataSource.getDsType() == VOLATILITY) {
      isVoatile = true;
      dataSourceRepository.saveAndFlush(dataSource);
    }

    // temporary 가 기존에 존재하는지 체크
    DataSourceTemporary temporary = null;
    if(StringUtils.isEmpty(temporaryId)) {
      temporaryId = PolarisUtils.randomUUID(DataSourceTemporary.ID_PREFIX, false);
    } else {
      temporary = temporaryRepository.findOne(temporaryId);
      if(temporary == null) {
        temporaryId = PolarisUtils.randomUUID(DataSourceTemporary.ID_PREFIX, false);
      } else if (temporary.getStatus() == ENABLE) {  // 기존 로드된 Temporary 데이터 소스가 있을경우 바로 리턴
        temporary.reloadExpiredTime();
        return temporaryRepository.save(temporary);
      }
    }

    // 엔진에서 사용할 데이터 소스 이름 지정
    String engineName = null;
    if(temporary == null) {
      engineName = dataSource.getEngineName() + '_' + PolarisUtils.randomString(5);
    } else {
      engineName = temporary.getName();
    }

    LOGGER.debug("Start to load temporary datasource.");

    sendTopic(sendTopicUri, new ProgressResponse(0, "START_LOAD_TEMP_DATASOURCE"));

    // JDBC 로 부터 File 을 Import 함
    LinkIngestionInfo info = (LinkIngestionInfo) Preconditions.checkNotNull(dataSource.getIngestionInfo());
    DataConnection connection = Preconditions.checkNotNull(dataSource.getJdbcConnectionForIngestion(), "Connection info. required");

    List<String> tempResultFile;
    sendTopic(sendTopicUri, new ProgressResponse(5, "PROGRESS_GET_DATA_FROM_LINK_DATASOURCE"));
    try {
      tempResultFile = jdbcConnectionService
          .selectQueryToCsv((JdbcDataConnection) connection,
                            info,
                            engineProperties.getQuery().getLocalBaseDir(),
                            engineName,
                            dataSource.getFields(), filters, maxRow);
    } catch (Exception e) {
      sendTopic(sendTopicUri, new ProgressResponse(-1, "FAIL_TO_LOAD_LINK_DATASOURCE"));
      throw new DataSourceIngetionException("Fail to create temporary file : " + e.getMessage());
    }

    if(CollectionUtils.isEmpty(tempResultFile)) {
      sendTopic(sendTopicUri, new ProgressResponse(-1, "FAIL_TO_LOAD_LINK_DATASOURCE"));
      throw new DataSourceIngetionException("Fail to create temporary file ");
    }

    String tempFile = tempResultFile.get(0);

    // Check Timestamp Field
    if(!dataSource.existTimestampField()) {
      // find timestamp field in datasource
      List<Field> timeFields = dataSource.getFields().stream()
                                         .filter(field -> field.getLogicalType() == LogicalType.TIMESTAMP)
                                         .collect(Collectors.toList());

      // Time Field 처리
      // timestamp 타입의 필드가 없다면 current timestamp 를 전달하고,
      // 있다면 맨 처음 timestamp 타입의 필드를 선택
      if (timeFields == null || timeFields.size() == 0) {
        Field field = new Field(FIELD_NAME_CURRENT_TIMESTAMP, DataType.TIMESTAMP, Field.FieldRole.TIMESTAMP, 0L);
        dataSource.addField(field);

        String dateStr = DateTime.now().toString();
        tempFile = PolarisUtils.addTimestampColumnFromCsv(dateStr, tempFile, tempFile + "_ts");
      } else {
        for (int i = 0; i < timeFields.size(); i++) {
          if (i == 0) {
            timeFields.get(i).setRole(Field.FieldRole.TIMESTAMP);
          } else {
            timeFields.get(i).setRole(Field.FieldRole.DIMENSION);
          }
        }
      }
    }

    LOGGER.debug("Created result file from source : {}", tempFile);

    // Send Remote Broker
    String remoteFile = copyLocalToRemoteBroker(tempFile);
    LOGGER.debug("Send result file to broker : {}", remoteFile);

    sendTopic(sendTopicUri, new ProgressResponse(10, "COMPLETE_GET_DATA_FROM_LINK_DATASOURCE"));

    // Bulk load from result file.
    Map<String, Object> paramMap = Maps.newHashMap();
    paramMap.put("async", async);
    paramMap.put("temporary", false);

    BulkLoadSpec spec = new BulkLoadSpecBuilder(dataSource)
        .name(engineName)
        .path(Lists.newArrayList(remoteFile))
        .build();

    String specStr = GlobalObjectMapper.writeValueAsString(spec);

    LOGGER.info("Start to load to druid, async: {}, Spec: {}", async, specStr);
    Map<String, Object> result = engineRepository.load(specStr, paramMap, Map.class)
                                                 .orElseThrow(() -> new DataSourceIngetionException("Result empty"));

    LOGGER.info("Successfully load. Result is ", result);

    if(temporary == null) {
      temporary =
          new DataSourceTemporary(temporaryId, engineName, dataSource.getId(),
                                  (String) result.get("queryId"), info.getExpired(),
                                  GlobalObjectMapper.writeListValueAsString(filters, Filter.class),
                                  isVoatile, async);
    }

    temporaryRepository.saveAndFlush(temporary);
    long checkPeriod = Math.round(timeout / 50) * 1000L;
    if(async) { // Send message to complete loading temporary datasource

      boolean succeed = false;
      int count = 1;
      while (true) {
        // 50 회 loop 이면 종료
        if(count == 90) {
          break;
        } else {
          sendTopic(sendTopicUri, new ProgressResponse(count + 10, "PROGRESS_LOAD_TEMP_DATASOURCE"));
        }

        // timeout 값에 따른 checkPeriod 에 따른 상태 체크
        try {
          Thread.sleep(checkPeriod);
        } catch (InterruptedException e) {}

        LOGGER.debug("Check temporary datasource [{}] : {}", count, temporaryId);
        if(checkExistLoadDataSource(engineName)) {
          succeed = true;
          break;
        }
        count++;
      }

      if(succeed) {
        temporary.setStatus(ENABLE);
      } else {
        // 이시점에서 실패하면 어쩌지?
        temporary.setStatus(FAIL);
        sendTopic(sendTopicUri, new ProgressResponse(-1, "TIMEOUT_LOAD_TEMP_DATASOURCE"));
      }
    } else {
      temporary.setStatus(ENABLE);
    }

    // 적재가 완료된 순간 이후 부터 reset
    temporary.reloadExpiredTime();

    sendTopic(sendTopicUri, new ProgressResponse(100, "COMPLETE_LOAD_TEMP_DATASOURCE"));

    return temporaryRepository.saveAndFlush(temporary);

  }

  public void deleteLoadDataSource(String datasourceName) {
    engineMetaRepository.purgeDataSource(datasourceName);
  }

  public List<String> findLoadDataSourceNames() {
    return engineMetaRepository.findLoadDataSources();
  }

  public List<DataSourceTemporary> findTemporaryByDataSources(String dataSourceId) {

    List<DataSourceTemporary> temporaries = temporaryRepository.findAll();
    if(CollectionUtils.isEmpty(temporaries)) {
      return temporaries;
    }

    final List<String> listDataSource = findLoadDataSourceNames();

    return temporaries.stream()
               .filter(temp -> listDataSource.contains(temp.getName()))
               .collect(Collectors.toList());
  }

  public boolean checkExistLoadDataSource(String dataSourceName) {

    String result = engineMetaRepository.findLoadDataSourceInfo(dataSourceName);

    LOGGER.debug("load Datasource info : {}", result);

    return StringUtils.isEmpty(result) ? false : true;
  }

  /**
   * Broker node 에 위치한 서버에 파일 업로드, Local 일 경우 Empty
   *
   * @return Dedicated worker host, if success.
   */
  private String copyLocalToRemoteBroker(String uploadFileName) {

    FileLoaderProperties properties = engineProperties.getQuery().getLoader();
    if(properties == null) {
      return uploadFileName;
    }

    List<String> remotePaths = fileLoaderFactory.put(properties, uploadFileName);

    return remotePaths.get(0);

  }

//  private String copyLocalToRemoteBroker(String uploadFileName) {
//
//    EngineProperties.QueryInfo queryInfo = engineProperties.getQuery();
//
//    Map<String, EngineProperties.Host> brokers = queryInfo.getHosts();
//
//    // 여러개의 브로커가 존재할 경우 처리
//    String remotePath = queryInfo.getLocalResultDir() + File.separator + new File(uploadFileName).getName();
//
//    if(MapUtils.isEmpty(brokers)) {
//      LOGGER.debug("It's local broker!");
//      return uploadFileName;
//    }
//
//    String brokerHostname = null;
//    EngineProperties.Host brokerHostInfo = null;
//    for (String key : brokers.keySet()) {
//      brokerHostname = key;
//      brokerHostInfo = brokers.get(key);
//
//      if("localhost".equals(brokerHostname)) {
//        LOGGER.debug("It's local broker!");
//        return uploadFileName;
//      }
//
//      try {
//        SshUtils.copyLocalToRemoteFileByScp(Lists.newArrayList(uploadFileName),
//                                            queryInfo.getLocalResultDir(),
//                                            brokerHostname,
//                                            brokerHostInfo.getPort(),
//                                            brokerHostInfo.getUsername(),
//                                            brokerHostInfo.getPassword());
//      } catch (Exception e) {
//        LOGGER.error("Fail to copy local files to {}", brokerHostname);
//        throw new RuntimeException("Fail to copy local files to " + brokerHostname);
//      }
//      LOGGER.info("Successfully copy local files({}) to {}", uploadFileName, brokerHostname);
//    }
//
//    return remotePath;
//
//  }

  class ProgressTimerTask implements Runnable {

    private String dataSourceName;

    private boolean success;

    private AtomicInteger increment = new AtomicInteger();

    public ProgressTimerTask(String dataSourceName) {
      this.dataSourceName = dataSourceName;
    }

    @Override
    public void run() {
      increment.getAndIncrement();

      if(checkExistLoadDataSource(dataSourceName)) {
        success = true;
        Thread.currentThread().interrupt();
      }
    }

    public boolean isSuccess() {
      return success;
    }

    public AtomicInteger getIncrement() {
      return increment;
    }
  }
}
