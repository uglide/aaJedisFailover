Note this example was co-authored with Brandon Amos (another SA from Redis) - 

This example showcases how Jedis can be configured to failover between two instances of Redis

The Main class runs a loop where it writes to a key repeatedly.  If the redis instance it is talking to becomes unavailable, it  switches to a back up instance after a configured # of retries.

At the moment of the switch, a callback method is invoked which prints out the message indicating that the switch has ocurred.

The configuration is done in code that creates a connectionHelper instance using two arguments - each being a configuration object of the type: JedisConnectionHelperSettings.
Each of the JedisConnectionHelperSettings objects holds the information to connect to a particular redis endpoint:

```
    public JedisConnectionHelper(JedisConnectionHelperSettings bs, JedisConnectionHelperSettings bs2){
        System.out.println("Creating JedisConnectionHelper for failover with "+bs+" \nand\n "+bs2);
        JedisClientConfig config = DefaultJedisClientConfig.builder().user(bs.getUserName()).password(bs.getPassword()).build();
        JedisClientConfig config2 = DefaultJedisClientConfig.builder().user(bs2.getUserName()).password(bs2.getPassword()).build();

        ClusterConfig[] clientConfigs = new ClusterConfig[2];
        clientConfigs[0] = new ClusterConfig(new HostAndPort(bs.getRedisHost(), bs.getRedisPort()), config);
        clientConfigs[1] = new ClusterConfig(new HostAndPort(bs2.getRedisHost(), bs2.getRedisPort()), config2);

        MultiClusterClientConfig.Builder builder = new MultiClusterClientConfig.Builder(clientConfigs);
        builder.circuitBreakerSlidingWindowSize(10);
        builder.circuitBreakerSlidingWindowMinCalls(1);
        builder.circuitBreakerFailureRateThreshold(50.0f);

        MultiClusterPooledConnectionProvider provider = new MultiClusterPooledConnectionProvider(builder.build());

        FailoverReporter reporter = new FailoverReporter();
        provider.setClusterFailoverPostProcessor(reporter);

        this.unifiedJedis = new UnifiedJedis(provider);

        this.connectionProvider = null;
        this.jedisPooled = null;
    }  
```

A sample run of this program where two instances of Redis are utilized would be:

``` 
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--failover true --host mydb.host.1.org --port 10900 --password pass1 --host2 mydb.host.2.org --port2 10900 --password2 pass2"
```
And with additional logging of the underlying circuitBreaker logic:
``` 
mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--failover true --host mydb.host.1.org --port 10900 --host2 mydb.host.2.org --port2 10900" -Dorg.slf4j.simpleLogger.log.redis.clients.jedis=TRACE
```

Note that the multThreaded test publishes to a pubsub channel it is good to listen in and watch as it pauses and resumes during a failover event
* from redis-cli (once you are connected to the backup database) issue:
``` 
subscribe ps:Messages
```
Then, disable, kill the first database and watch as the messages pause and then pick up again as the client threads failover