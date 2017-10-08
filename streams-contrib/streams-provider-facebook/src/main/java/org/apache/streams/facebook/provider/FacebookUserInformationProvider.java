/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.streams.facebook.provider;

import org.apache.streams.config.StreamsConfigurator;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.core.StreamsProvider;
import org.apache.streams.core.StreamsResultSet;
import org.apache.streams.facebook.FacebookUserInformationConfiguration;
import org.apache.streams.jackson.StreamsJacksonMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.MoreExecutors;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigRenderOptions;
import facebook4j.Facebook;
import facebook4j.FacebookException;
import facebook4j.FacebookFactory;
import facebook4j.Friend;
import facebook4j.Paging;
import facebook4j.ResponseList;
import facebook4j.User;
import facebook4j.conf.ConfigurationBuilder;
import org.apache.commons.lang.NotImplementedException;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class FacebookUserInformationProvider implements StreamsProvider, Serializable {

  public static final String STREAMS_ID = "FacebookUserInformationProvider";

  private static final Logger LOGGER = LoggerFactory.getLogger(FacebookUserInformationProvider.class);

  private static final ObjectMapper mapper = StreamsJacksonMapper.getInstance();

  private static final String ALL_PERMISSIONS = "ads_management,ads_read,create_event,create_note,email,export_stream,friends_about_me,friends_actions.books,friends_actions.music,friends_actions.news,friends_actions.video,friends_activities,friends_birthday,friends_education_history,friends_events,friends_games_activity,friends_groups,friends_hometown,friends_interests,friends_likes,friends_location,friends_notes,friends_online_presence,friends_photo_video_tags,friends_photos,friends_questions,friends_relationship_details,friends_relationships,friends_religion_politics,friends_status,friends_subscriptions,friends_videos,friends_website,friends_work_history,manage_friendlists,manage_notifications,manage_pages,photo_upload,publish_actions,publish_stream,read_friendlists,read_insights,read_mailbox,read_page_mailboxes,read_requests,read_stream,rsvp_event,share_item,sms,status_update,user_about_me,user_actions.books,user_actions.music,user_actions.news,user_actions.video,user_activities,user_birthday,user_education_history,user_events,user_friends,user_games_activity,user_groups,user_hometown,user_interests,user_likes,user_location,user_notes,user_online_presence,user_photo_video_tags,user_photos,user_questions,user_relationship_details,user_relationships,user_religion_politics,user_status,user_subscriptions,user_videos,user_website,user_work_history,video_upload,xmpp_login";
  private FacebookUserInformationConfiguration facebookUserInformationConfiguration;

  private Class klass;
  protected volatile Queue<StreamsDatum> providerQueue = new LinkedBlockingQueue<>();

  public FacebookUserInformationConfiguration getConfig() {
    return facebookUserInformationConfiguration;
  }

  public void setConfig(FacebookUserInformationConfiguration config) {
    this.facebookUserInformationConfiguration = config;
  }

  protected Iterator<String[]> idsBatches;

  protected ExecutorService executor;

  protected DateTime start;
  protected DateTime end;

  protected final AtomicBoolean running = new AtomicBoolean();

  private static ExecutorService newFixedThreadPoolWithQueueSize(int numThreads, int queueSize) {
    return new ThreadPoolExecutor(numThreads, numThreads,
        5000L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueSize, true), new ThreadPoolExecutor.CallerRunsPolicy());
  }

  /**
   * FacebookUserInformationProvider constructor - resolves FacebookUserInformationConfiguration from JVM 'facebook'.
   */
  public FacebookUserInformationProvider() {
    Config config = StreamsConfigurator.getConfig().getConfig("facebook");
    FacebookUserInformationConfiguration facebookUserInformationConfiguration;
    try {
      facebookUserInformationConfiguration = mapper.readValue(config.root().render(ConfigRenderOptions.concise()), FacebookUserInformationConfiguration.class);
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }

  /**
   * FacebookUserInformationProvider constructor - uses supplie FacebookUserInformationConfiguration.
   * @param config
   */
  public FacebookUserInformationProvider(FacebookUserInformationConfiguration config) {
    this.facebookUserInformationConfiguration = config;
  }

  public FacebookUserInformationProvider(Class klass) {
    Config config = StreamsConfigurator.getConfig().getConfig("facebook");
    FacebookUserInformationConfiguration facebookUserInformationConfiguration;
    try {
      facebookUserInformationConfiguration = mapper.readValue(config.root().render(ConfigRenderOptions.concise()), FacebookUserInformationConfiguration.class);
    } catch (IOException ex) {
      ex.printStackTrace();
      return;
    }
    this.klass = klass;
  }

  public FacebookUserInformationProvider(FacebookUserInformationConfiguration config, Class klass) {
    this.facebookUserInformationConfiguration = config;
    this.klass = klass;
  }

  public Queue<StreamsDatum> getProviderQueue() {
    return this.providerQueue;
  }

  @Override
  public String getId() {
    return STREAMS_ID;
  }

  @Override
  public void startStream() {
    running.set(true);
  }

  @Override
  public StreamsResultSet readCurrent() {

    Preconditions.checkArgument(idsBatches.hasNext());

    LOGGER.info("readCurrent");

    Facebook client = getFacebookClient();

    try {
      User me = client.users().getMe();
      String json = mapper.writeValueAsString(me);
      providerQueue.add(
          new StreamsDatum(json, DateTime.now())
      );
    } catch (JsonProcessingException | FacebookException ex) {
      ex.printStackTrace();
    }

    if (idsBatches.hasNext()) {
      while (idsBatches.hasNext()) {
        try {
          List<User> userList = client.users().getUsers(idsBatches.next());
          for (User user : userList) {

            try {
              String json = mapper.writeValueAsString(user);
              providerQueue.add(
                  new StreamsDatum(json, DateTime.now())
              );
            } catch (JsonProcessingException ex) {
              LOGGER.trace("JsonProcessingException", ex);
            }
          }

        } catch (FacebookException ex) {
          ex.printStackTrace();
        }
      }
    } else {
      try {
        ResponseList<Friend> friendResponseList = client.friends().getFriends();
        Paging<Friend> friendPaging;
        do {

          for ( Friend friend : friendResponseList ) {

            String json;
            try {
              json = mapper.writeValueAsString(friend);
              providerQueue.add(
                  new StreamsDatum(json)
              );
            } catch (JsonProcessingException ex) {
              LOGGER.trace("JsonProcessingException", ex);
            }
          }
          friendPaging = friendResponseList.getPaging();
          friendResponseList = client.fetchNext(friendPaging);
        }
        while ( friendPaging != null
                &&
                friendResponseList != null );
      } catch (FacebookException ex) {
        ex.printStackTrace();
      }

    }

    LOGGER.info("Finished.  Cleaning up...");

    LOGGER.info("Providing {} docs", providerQueue.size());

    StreamsResultSet result =  new StreamsResultSet(providerQueue);
    running.set(false);

    LOGGER.info("Exiting");

    return result;

  }

  @Override
  public StreamsResultSet readNew(BigInteger sequence) {
    LOGGER.debug("{} readNew", STREAMS_ID);
    throw new NotImplementedException();
  }

  @Override
  public StreamsResultSet readRange(DateTime start, DateTime end) {
    LOGGER.debug("{} readRange", STREAMS_ID);
    this.start = start;
    this.end = end;
    readCurrent();
    return (StreamsResultSet)providerQueue.iterator();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  void shutdownAndAwaitTermination(ExecutorService pool) {
    pool.shutdown(); // Disable new tasks from being submitted
    try {
      // Wait a while for existing tasks to terminate
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
        pool.shutdownNow(); // Cancel currently executing tasks
        // Wait a while for tasks to respond to being cancelled
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
          System.err.println("Pool did not terminate");
        }
      }
    } catch (InterruptedException ie) {
      // (Re-)Cancel if current thread also interrupted
      pool.shutdownNow();
      // Preserve interrupt status
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void prepare(Object configurationObject) {

    executor = MoreExecutors.listeningDecorator(newFixedThreadPoolWithQueueSize(5, 20));

    Objects.requireNonNull(providerQueue);
    Objects.requireNonNull(this.klass);
    Objects.requireNonNull(facebookUserInformationConfiguration.getOauth().getAppId());
    Objects.requireNonNull(facebookUserInformationConfiguration.getOauth().getAppSecret());
    Objects.requireNonNull(facebookUserInformationConfiguration.getOauth().getUserAccessToken());
    Objects.requireNonNull(facebookUserInformationConfiguration.getInfo());

    List<String> ids = new ArrayList<>();
    List<String[]> idsBatches = new ArrayList<>();

    for (String s : facebookUserInformationConfiguration.getInfo()) {
      if (s != null) {
        ids.add(s);

        if (ids.size() >= 100) {
          // add the batch
          idsBatches.add(ids.toArray(new String[ids.size()]));
          // reset the Ids
          ids = new ArrayList<>();
        }

      }
    }

    if (ids.size() > 0) {
      idsBatches.add(ids.toArray(new String[ids.size()]));
    }

    this.idsBatches = idsBatches.iterator();
  }

  protected Facebook getFacebookClient() {
    ConfigurationBuilder cb = new ConfigurationBuilder();
    cb.setDebugEnabled(true)
        .setOAuthAppId(facebookUserInformationConfiguration.getOauth().getAppId())
        .setOAuthAppSecret(facebookUserInformationConfiguration.getOauth().getAppSecret())
        .setOAuthAccessToken(facebookUserInformationConfiguration.getOauth().getUserAccessToken())
        .setOAuthPermissions(ALL_PERMISSIONS)
        .setJSONStoreEnabled(true)
        .setClientVersion("v1.0");

    FacebookFactory ff = new FacebookFactory(cb.build());

    return ff.getInstance();
  }

  @Override
  public void cleanUp() {
    shutdownAndAwaitTermination(executor);
  }
}
