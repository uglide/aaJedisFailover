package com.redislabs.sa.ot.aajf;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.JedisPooled;


public class TestMultiThread implements Runnable{
    UnifiedJedis connectionPool;
    int testThreadNumber = 0;
    long howManyMessages=0;
    String testType = "default";
    static volatile boolean exceptionCaught=false;

    public TestMultiThread setTestThreadNumber(int testThreadNumber){
        this.testThreadNumber=testThreadNumber;
        return this;
    }

    public TestMultiThread(UnifiedJedis connectionPool,String testType,long howManyMessages){
        this.connectionPool = connectionPool;
        this.testType = testType;
        this.howManyMessages = howManyMessages;
    }

    public static void fireTest(UnifiedJedis connectionPool,int howManyThreads, String testType, long howManyMessages){
        connectionPool.del("TestMultiThread:"+testType+"Threads:Complete");//cleanup for this test
        long startTime = System.currentTimeMillis();
        System.out.println("\n\tHere come the threads for test: "+testType+"\n");
        for(int x=0;x<howManyThreads;x++){
            System.out.print(x+" ");
            new Thread(new TestMultiThread(connectionPool,testType,howManyMessages).setTestThreadNumber(x)).start();
        }
        int completedThreads = 0;
        while(completedThreads < howManyThreads){
            try {
                Thread.sleep(10);
                completedThreads = Integer.parseInt(connectionPool.get("TestMultiThread:"+testType+"Threads:Complete"));
            }catch(Throwable t){}
        }
        System.out.println("\n\n*****************\n" +
                testType+":Test took :"+((System.currentTimeMillis()-startTime)/1000)+" seconds" +
                "\n"+testType+":TEST IS COMPLETE... ");
    }

    @Override
    public void run() {
        for(int x=0;x<howManyMessages;x++){
            String connectionInstance = ""+connectionPool;
            connectionPool.publish("ps:Messages",connectionInstance+":testThread# "+this.testThreadNumber+" message #"+x);
            connectionPool.set("tmt:String", ""+x);
            try{
                Thread.sleep(20);
            }catch(Throwable t){
                //do nothing
            }
            if(x>1000){
                if(!exceptionCaught){
                    try{
                        connectionPool.set("tmt:String", "y");
                        connectionPool.incr("tmt:String");
                    }catch(Throwable tt){
                        exceptionCaught=true;
                    }
                }
            }
        }
        connectionPool.incr("TestMultiThread:"+this.testType+"Threads:Complete");
    }
}
