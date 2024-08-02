package com.redislabs.sa.ot.aajf;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.*;
import redis.clients.jedis.providers.PooledConnectionProvider;

import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisConnectionException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import redis.clients.jedis.providers.MultiClusterPooledConnectionProvider;
import redis.clients.jedis.MultiClusterClientConfig;
import redis.clients.jedis.MultiClusterClientConfig.ClusterConfig;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.MultiClusterClientConfig.Builder;

import java.util.function.Consumer;


/**
 You can run this program either with --failover true AND the full set of args:
 ie: --host xxx and --host2 yyy etc...  or you can leave out the --failover argument and only
 connect to a single Redis database
 mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host FIXME --port FIXME --password FIXME"
 mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host redis-12001.bamos1-tf-us-west-2-cluster.redisdemo.com --port 12001"

 below is an example of providing the args for a failover scenario:
 mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--failover true --host FIXME --port FIXME --password FIXME --host2 FIXME --port2 FIXME --password2 FIXME"

 */
public class Main
{
    public static void main( String[] args )
    {
        /**
         Only UnifiedJedis allows for auto-failover behaviors
         If --failover true is NOT passed in, a JedisPooled will be created
         ^ this is good, and allows for the best pooled connection management while also allowing
         the creation of the failover-capable UnifiedJedis when that is desired
         **/
        UnifiedJedis connection= JedisConnectionHelper.initRedisConnection(args);
        connection.set("hello","world");
        System.out.println( connection.get("hello"));
        System.out.println( connection.toString());
        System.out.println("Starting Failover Test, writing to first cluster");

        //public ZRangeParams(double min, double max) <-- byscore is implicit with this constructor
        double min = 0; double max = 5000;
        redis.clients.jedis.params.ZRangeParams params = null;
        long opssCounter = 0;
        long targetOppsCount = 10000;
        long startTime = System.currentTimeMillis();
        for (int x = 1; x<(targetOppsCount+1); x++){
            try{
                min = x;
                params = new redis.clients.jedis.params.ZRangeParams(min,max);
                connection.zadd(connection+":key",x,connection+":"+x);
                //System.out.println( );
                connection.zrange(connection+":key",params);
                safeIncrement(connection,"testIncrString","1",""+System.nanoTime());
                safeIncrement(connection,"testIncrString","2",""+System.nanoTime());
                opssCounter++;
                // throw a DataException to cause failover See lines 353-357 or so where DataException is added
                // you may not want that behavior...
                if(opssCounter==1000){
                    connection.set("x", "y");
                    connection.incr("x");
                }
            }catch(Throwable ste){
                ste.printStackTrace();
                try {
                    Thread.sleep(2000);
                } catch(Throwable t) {
                    t.printStackTrace();
                }
            }
        }
        System.out.println("\n\nTime taken to execute "+opssCounter + " was "+((System.currentTimeMillis()-startTime)/1000)+" seconds");
    }

    static void safeIncrement(UnifiedJedis jedis,String stringKeyName, String routingValue, String uuid) {
        //SortedSet API offers ZCARD and ZCOUNT:
        //stringKeyName is the String being incremented
        //routingValue is the value added to the string keyname to route it to a slot in redis
        // a co-located SortedSet key is derived from that string keyname and routingValue
        //if string keyname is bob and routingValue is 1 sortedSet keyname is z:bob{1}
        //args to script are:
        //routingValue, (used to route execution of the script to a shard)
        //stringKeyName,
        //uuid for current update attempt,
        //incr_amnt (in case we don't just want to add a single integer to the string counter)
        //this script removed any entries stored in the SortedSet that are older than
        //current time in seconds - 100 seconds
        String luaScript =
                "local stringKeyNameWithRouting = ARGV[1]..'{'..KEYS[1]..'}' "+
                        "local ssname = 'z:'..stringKeyNameWithRouting "+
                        "local uuid = ARGV[2] "+
                        "local incr_amnt = ARGV[3] "+
                        "local ts_score = redis.call('TIME')[1] "+
                        "local result = 'duplicate [fail]' "+
                        "if redis.call('ZINCRBY',ssname,ts_score,uuid) == ts_score then "+
                        "redis.call('incrby',stringKeyNameWithRouting,incr_amnt) "+
                        "redis.call('ZREMRANGEBYRANK', ssname, (ts_score-100), 0) "+
                        "result = 'success' end return {ssname,result}";
        long timestamp = System.currentTimeMillis();
        Object luaResponse = jedis.eval(luaScript,1,routingValue,stringKeyName,""+uuid,"100");
        //System.out.println("\nResults from Lua: [SortedSetKeyName] [result]  \n"+luaResponse);
        //System.out.println("\n\nrunning the lua script with dedup and incr logic took "+(System.currentTimeMillis()-timestamp+" milliseconds"));
    }

}

class JedisConnectionHelper {
    final PooledConnectionProvider connectionProvider;
    final JedisPooled jedisPooled;
    final UnifiedJedis unifiedJedis;
    //connection establishment
    static UnifiedJedis initRedisConnection(String[] args){
        boolean isFailover = false;
        JedisConnectionHelperSettings settings = new JedisConnectionHelperSettings();
        JedisConnectionHelperSettings settings2 = null; // in case we are failing over

        ArrayList<String> argList = new ArrayList<>(Arrays.asList(args));
        if (argList.contains("--host")) {
            int argIndex = argList.indexOf("--host");
            settings.setRedisHost(argList.get(argIndex + 1));
        }
        if (argList.contains("--port")) {
            int argIndex = argList.indexOf("--port");
            settings.setRedisPort(Integer.parseInt(argList.get(argIndex + 1)));
        }
        if (argList.contains("--user")) {
            int argIndex = argList.indexOf("--user");
            settings.setUserName(argList.get(argIndex + 1));
        }
        if (argList.contains("--password")) {
            int argIndex = argList.indexOf("--password");
            settings.setPassword(argList.get(argIndex + 1));
            settings.setUsePassword(true);
        }
        if (argList.contains("--usessl")) {
            int argIndex = argList.indexOf("--usessl");
            boolean useSSL = Boolean.parseBoolean(argList.get(argIndex + 1));
            System.out.println("loading custom --usessl == " + useSSL);
            settings.setUseSSL(useSSL);
        }
        if (argList.contains("--cacertpath")) {
            int argIndex = argList.indexOf("--cacertpath");
            String caCertPath = argList.get(argIndex + 1);
            System.out.println("loading custom --cacertpath == " + caCertPath);
            settings.setCaCertPath(caCertPath);
        }
        if (argList.contains("--cacertpassword")) {
            int argIndex = argList.indexOf("--cacertpassword");
            String caCertPassword = argList.get(argIndex + 1);
            System.out.println("loading custom --cacertpassword == " + caCertPassword);
            settings.setCaCertPassword(caCertPassword);
        }
        if (argList.contains("--usercertpath")) {
            int argIndex = argList.indexOf("--usercertpath");
            String userCertPath = argList.get(argIndex + 1);
            System.out.println("loading custom --usercertpath == " + userCertPath);
            settings.setUserCertPath(userCertPath);
        }
        if (argList.contains("--usercertpass")) {
            int argIndex = argList.indexOf("--usercertpass");
            String userCertPassword = argList.get(argIndex + 1);
            System.out.println("loading custom --usercertpass == " + userCertPassword);
            settings.setUserCertPassword(userCertPassword);
        }
        // when turning on failover add --failover true
        if (argList.contains("--failover")) {
            int argIndex = argList.indexOf("--failover");
            isFailover = Boolean.parseBoolean(argList.get(argIndex + 1));
        }
        settings.setTestOnBorrow(true);
        settings.setConnectionTimeoutMillis(120000);
        settings.setNumberOfMinutesForWaitDuration(1);
        settings.setNumTestsPerEvictionRun(10);
        settings.setPoolMaxIdle(1); //this means less stale connections
        settings.setPoolMinIdle(0);
        settings.setRequestTimeoutMillis(12000);
        settings.setTestOnReturn(false); // if idle, they will be mostly removed anyway
        settings.setTestOnCreate(true);

        if (isFailover){
            settings2 = new JedisConnectionHelperSettings();
            if (argList.contains("--host2")) {
                int argIndex = argList.indexOf("--host2");
                settings2.setRedisHost(argList.get(argIndex + 1));
            }
            if (argList.contains("--port2")) {
                int argIndex = argList.indexOf("--port2");
                settings2.setRedisPort(Integer.parseInt(argList.get(argIndex + 1)));
            }
            if (argList.contains("--user2")) {
                int argIndex = argList.indexOf("--user2");
                settings2.setUserName(argList.get(argIndex + 1));
            }
            if (argList.contains("--password2")) {
                int argIndex = argList.indexOf("--password2");
                settings2.setPassword(argList.get(argIndex + 1));
                settings2.setUsePassword(true);
            }
            JedisConnectionHelper failoverHelper = null;
            try{
                // only use a single connection based on the hostname (not ipaddress) if possible
                failoverHelper = new JedisConnectionHelper(settings,settings2);
            }catch(Throwable t){
                t.printStackTrace();
                try{
                    Thread.sleep(4000);
                }catch(InterruptedException ie){}
                // give it another go - in case the first attempt was just unlucky:
                // only use a single connection based on the hostname (not ipaddress) if possible
                failoverHelper = new JedisConnectionHelper(settings,settings2);
            }
            return failoverHelper.getUnifiedJedis();
        }
        else {
            JedisConnectionHelper connectionHelper = null;
            try{
                // only use a single connection based on the hostname (not ipaddress) if possible
                connectionHelper = new JedisConnectionHelper(settings);
            }catch(Throwable t){
                t.printStackTrace();
                try{
                    Thread.sleep(4000);
                }catch(InterruptedException ie){}
                // give it another go - in case the first attempt was just unlucky:
                // only use a single connection based on the hostname (not ipaddress) if possible
                connectionHelper = new JedisConnectionHelper(settings);
            }
            return (UnifiedJedis) connectionHelper.getPooledJedis();
        }
    }

    /**
     * Used when you want to send a batch of commands to the Redis Server
     * @return Pipeline
     */
    public Pipeline getPipeline(){
        return  new Pipeline(jedisPooled.getPool().getResource());
    }


    /**
     * Assuming use of Jedis 4.3.1:
     * https://github.com/redis/jedis/blob/82f286b4d1441cf15e32cc629c66b5c9caa0f286/src/main/java/redis/clients/jedis/Transaction.java#L22-L23
     * @return Transaction
     */
    public Transaction getTransaction(){
        return new Transaction(getPooledJedis().getPool().getResource());
    }

    /**
     * Obtain the default object used to perform Redis commands
     * @return JedisPooled
     */
    public JedisPooled getPooledJedis(){
        return jedisPooled;
    }

    /**
     * Obtain the default object used to perform Redis commands
     * @return UnifiedJedis
     */
    public UnifiedJedis getUnifiedJedis(){
        return unifiedJedis;
    }

    /**
     * Use this to build the URI expected in this classes' Constructor
     * @param host
     * @param port
     * @param username
     * @param password
     * @return
     */
    public static URI buildURI(String host, int port, String username, String password){
        URI uri = null;
        try {
            if (!("".equalsIgnoreCase(password))) {
                uri = new URI("redis://" + username + ":" + password + "@" + host + ":" + port);
            } else {
                uri = new URI("redis://" + host + ":" + port);
            }
        } catch (URISyntaxException use) {
            use.printStackTrace();
            System.exit(1);
        }
        return uri;
    }

    private static SSLSocketFactory createSslSocketFactory(
            String caCertPath, String caCertPassword, String userCertPath, String userCertPassword)
            throws IOException, GeneralSecurityException {

        KeyStore keyStore = KeyStore.getInstance("pkcs12");
        keyStore.load(new FileInputStream(userCertPath), userCertPassword.toCharArray());

        KeyStore trustStore = KeyStore.getInstance("jks");
        trustStore.load(new FileInputStream(caCertPath), caCertPassword.toCharArray());

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
        trustManagerFactory.init(trustStore);

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("PKIX");
        keyManagerFactory.init(keyStore, userCertPassword.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

        return sslContext.getSocketFactory();
    }

    public JedisConnectionHelper(JedisConnectionHelperSettings bs, JedisConnectionHelperSettings bs2){
        System.out.println("Creating JedisConnectionHelper for failover with "+bs+" \nand\n "+bs2);
        // no SSL yet
        JedisClientConfig config = null;
        JedisClientConfig config2 = null;
        if(bs.isUsePassword()){
            config = DefaultJedisClientConfig.builder().user(bs.getUserName()).password(bs.getPassword()).build();
            config2 = DefaultJedisClientConfig.builder().user(bs2.getUserName()).password(bs2.getPassword()).build();
        }else{
            config = DefaultJedisClientConfig.builder().user(bs.getUserName()).build();
            config2 = DefaultJedisClientConfig.builder().user(bs2.getUserName()).build();
        }
        redis.clients.jedis.MultiClusterClientConfig.ClusterConfig[] clientConfigs = new redis.clients.jedis.MultiClusterClientConfig.ClusterConfig[2];
        clientConfigs[0] = new redis.clients.jedis.MultiClusterClientConfig.ClusterConfig(new HostAndPort(bs.getRedisHost(), bs.getRedisPort()), config);
        clientConfigs[1] = new redis.clients.jedis.MultiClusterClientConfig.ClusterConfig(new HostAndPort(bs2.getRedisHost(), bs2.getRedisPort()), config2);

        Builder builder = new Builder(clientConfigs);
        builder.circuitBreakerSlidingWindowSize(1);
        builder.circuitBreakerSlidingWindowMinCalls(1);
        builder.circuitBreakerFailureRateThreshold(50.0f);

        java.util.List<java.lang.Class> circuitBreakerList = new ArrayList<java.lang.Class>();
        circuitBreakerList.add(JedisConnectionException.class);
        circuitBreakerList.add(JedisDataException.class);
        builder.circuitBreakerIncludedExceptionList(circuitBreakerList);
        builder.retryMaxAttempts(0);

        MultiClusterPooledConnectionProvider provider = new MultiClusterPooledConnectionProvider(builder.build());
        FailoverReporter reporter = new FailoverReporter();
        provider.setClusterFailoverPostProcessor(reporter);
        provider.setActiveMultiClusterIndex(1);

        this.unifiedJedis = new UnifiedJedis(provider);

        this.connectionProvider = null;
        this.jedisPooled = null;
    }



    public JedisConnectionHelper(JedisConnectionHelperSettings bs){
        System.out.println("Creating JedisConnectionHelper with "+bs);
        URI uri = buildURI(bs.getRedisHost(), bs.getRedisPort(), bs.getUserName(),bs.getPassword());
        HostAndPort address = new HostAndPort(uri.getHost(), uri.getPort());
        JedisClientConfig clientConfig = null;
        if(bs.isUsePassword()){
            String user = uri.getAuthority().split(":")[0];
            String password = uri.getAuthority().split(":")[1];
            password = password.split("@")[0];
            System.out.println("\n\nUsing user: "+user+" / password l!3*^rs@"+password);
            clientConfig = DefaultJedisClientConfig.builder().user(user).password(password)
                    .connectionTimeoutMillis(bs.getConnectionTimeoutMillis()).socketTimeoutMillis(bs.getRequestTimeoutMillis()).build(); // timeout and client settings

        }
        else {
            clientConfig = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(bs.getConnectionTimeoutMillis()).socketTimeoutMillis(bs.getRequestTimeoutMillis()).build(); // timeout and client settings
        }
        if(bs.isUseSSL()){ // manage client-side certificates to allow SSL handshake for connections
            SSLSocketFactory sslFactory = null;
            try{
                sslFactory = createSslSocketFactory(
                        bs.getCaCertPath(),
                        bs.getCaCertPassword(), // use the password you specified for keytool command
                        bs.getUserCertPath(),
                        bs.getUserCertPassword() // use the password you specified for openssl command
                );
            }catch(Throwable sslStuff){
                sslStuff.printStackTrace();
                System.exit(1);
            }
            clientConfig = DefaultJedisClientConfig.builder().user(bs.getUserName()).password(bs.getPassword())
                    .connectionTimeoutMillis(bs.getConnectionTimeoutMillis()).socketTimeoutMillis(bs.getRequestTimeoutMillis())
                    .sslSocketFactory(sslFactory) // key/trust details
                    .ssl(true).build();
        }
        GenericObjectPoolConfig<Connection> poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxIdle(bs.getPoolMaxIdle());
        poolConfig.setMaxTotal(bs.getMaxConnections());
        poolConfig.setMinIdle(bs.getPoolMinIdle());
        poolConfig.setMaxWait(Duration.ofMinutes(bs.getNumberOfMinutesForWaitDuration()));
        poolConfig.setTestOnCreate(bs.isTestOnCreate());
        poolConfig.setTestOnBorrow(bs.isTestOnBorrow());
        poolConfig.setNumTestsPerEvictionRun(bs.getNumTestsPerEvictionRun());
        poolConfig.setBlockWhenExhausted(bs.isBlockWhenExhausted());
        poolConfig.setMinEvictableIdleTime(Duration.ofMillis(bs.getMinEvictableIdleTimeMilliseconds()));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(bs.getTimeBetweenEvictionRunsMilliseconds()));

        this.connectionProvider = new PooledConnectionProvider(new ConnectionFactory(address, clientConfig), poolConfig);
        this.jedisPooled = new JedisPooled(connectionProvider);
        this.unifiedJedis = null;
    }
}

class JedisConnectionHelperSettings {
    private String redisHost = "FIXME";
    private int redisPort = 6379;
    private String userName = "default";
    private String password = "";
    private int maxConnections = 10;
    private int connectionTimeoutMillis = 2000;
    private int requestTimeoutMillis = 2000;
    private int poolMaxIdle = 500;
    private int poolMinIdle = 50;
    private int numberOfMinutesForWaitDuration = 1;
    private boolean testOnCreate = true;
    private boolean testOnBorrow = true;
    private boolean testOnReturn = true;
    private int numTestsPerEvictionRun = 3;
    private boolean useSSL = false;
    private boolean usePassword = false;
    private long minEvictableIdleTimeMilliseconds = 30000;
    private long timeBetweenEvictionRunsMilliseconds = 1000;
    private boolean blockWhenExhausted = true;
    private String trustStoreFilePath = "";
    private String trustStoreType = "";
    private String caCertPath = "./truststore.jks";
    private String caCertPassword = "FIXME";
    private String userCertPath = "./redis-user-keystore.p12";
    private String userCertPassword = "FIXME";

    public String getRedisHost() {
        return redisHost;
    }

    public void setRedisHost(String redisHost) {
        this.redisHost = redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public void setRedisPort(int redisPort) {
        this.redisPort = redisPort;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        this.setPoolMaxIdle(Math.round(maxConnections/2));
        this.setPoolMinIdle(Math.round(maxConnections/10));
    }

    public int getConnectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    public void setConnectionTimeoutMillis(int connectionTimeoutMillis) {
        this.connectionTimeoutMillis = connectionTimeoutMillis;
    }

    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public int getPoolMaxIdle() {
        return poolMaxIdle;
    }

    public void setPoolMaxIdle(int poolMaxIdle) {
        this.poolMaxIdle = poolMaxIdle;
    }

    public int getPoolMinIdle() {
        return poolMinIdle;
    }

    public void setPoolMinIdle(int poolMinIdle) {
        this.poolMinIdle = poolMinIdle;
    }

    public int getNumberOfMinutesForWaitDuration() {
        return numberOfMinutesForWaitDuration;
    }

    public void setNumberOfMinutesForWaitDuration(int numberOfMinutesForWaitDuration) {
        this.numberOfMinutesForWaitDuration = numberOfMinutesForWaitDuration;
    }

    public boolean isTestOnCreate() {
        return testOnCreate;
    }

    public void setTestOnCreate(boolean testOnCreate) {
        this.testOnCreate = testOnCreate;
    }

    public boolean isTestOnBorrow() {
        return testOnBorrow;
    }

    public void setTestOnBorrow(boolean testOnBorrow) {
        this.testOnBorrow = testOnBorrow;
    }

    public boolean isTestOnReturn() {
        return testOnReturn;
    }

    public void setTestOnReturn(boolean testOnReturn) {
        this.testOnReturn = testOnReturn;
    }

    public int getNumTestsPerEvictionRun() {
        return numTestsPerEvictionRun;
    }

    public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        this.numTestsPerEvictionRun = numTestsPerEvictionRun;
    }

    public boolean isUseSSL() {
        return useSSL;
    }

    public void setUseSSL(boolean useSSL) {
        this.useSSL = useSSL;
    }

    public boolean isUsePassword() {
        return usePassword;
    }

    public void setUsePassword(boolean usePassword) {
        this.usePassword = usePassword;
    }

    public long getMinEvictableIdleTimeMilliseconds() {
        return minEvictableIdleTimeMilliseconds;
    }

    public void setMinEvictableIdleTimeMilliseconds(long minEvictableIdleTimeMilliseconds) {
        this.minEvictableIdleTimeMilliseconds = minEvictableIdleTimeMilliseconds;
    }

    public long getTimeBetweenEvictionRunsMilliseconds() {
        return timeBetweenEvictionRunsMilliseconds;
    }

    public void setTimeBetweenEvictionRunsMilliseconds(long timeBetweenEvictionRunsMilliseconds) {
        this.timeBetweenEvictionRunsMilliseconds = timeBetweenEvictionRunsMilliseconds;
    }

    public boolean isBlockWhenExhausted() {
        return blockWhenExhausted;
    }

    public void setBlockWhenExhausted(boolean blockWhenExhausted) {
        this.blockWhenExhausted = blockWhenExhausted;
    }

    public String toString(){
        return "\nRedisUserName = "+getUserName()+"\nUsePassword = "+isUsePassword()+"\nUseSSL = "+isUseSSL()+ "\nRedisHost = "+getRedisHost()+
                "\nRedisPort = "+getRedisPort()+"\nMaxConnections = "+getMaxConnections()+
                "\nRequestTimeoutMilliseconds = "+getRequestTimeoutMillis()+"\nConnectionTimeOutMilliseconds = "+
                getConnectionTimeoutMillis();
    }

    public String getTrustStoreFilePath() {
        return trustStoreFilePath;
    }

    public void setTrustStoreFilePath(String trustStoreFilePath) {
        this.trustStoreFilePath = trustStoreFilePath;
    }

    public String getTrustStoreType() {
        return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
    }

    public String getCaCertPath() {
        return caCertPath;
    }

    public void setCaCertPath(String caCertPath) {
        this.caCertPath = caCertPath;
    }

    public String getCaCertPassword() {
        return caCertPassword;
    }

    public void setCaCertPassword(String caCertPassword) {
        this.caCertPassword = caCertPassword;
    }

    public String getUserCertPath() {
        return userCertPath;
    }

    public void setUserCertPath(String userCertPath) {
        this.userCertPath = userCertPath;
    }

    public String getUserCertPassword() {
        return userCertPassword;
    }

    public void setUserCertPassword(String userCertPassword) {
        this.userCertPassword = userCertPassword;
    }
}


class FailoverReporter implements Consumer<String> {
    String currentClusterName = "not set";

    public String getCurrentClusterName(){
        return currentClusterName;
    }

    @Override
    public void accept(String clusterName) {
        this.currentClusterName=clusterName;
        System.out.println("<< FailoverReporter >>\nJedis failover to cluster: " + clusterName+"\n<< FailoverReporter >>");
    }
}