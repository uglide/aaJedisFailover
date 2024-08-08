package com.redislabs.sa.ot.aajf;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.JedisPooled;


public class TestMultiThread implements Runnable{
    UnifiedJedis connectionInstance;
    int testThreadNumber = 0;
    long howManyMessages=0;
    String testType = "default";
    static volatile boolean exceptionCaught=false;

    public TestMultiThread setTestThreadNumber(int testThreadNumber){
        this.testThreadNumber=testThreadNumber;
        return this;
    }

    public TestMultiThread(UnifiedJedis ufJedis,String testType,long howManyMessages){
        this.connectionInstance = ufJedis;
        this.testType = testType;
        this.howManyMessages = howManyMessages;
    }

    public static void fireTest(UnifiedJedis ufJedis,int howManyThreads, String testType, long howManyMessages){
        ufJedis.del("TestMultiThread:"+testType+"Threads:Complete");//cleanup for this test
        long startTime = System.currentTimeMillis();
        System.out.println("\n\tHere come the threads for test: "+testType+"\n");
        for(int x=0;x<howManyThreads;x++){
            System.out.print(x+" ");
            new Thread(new TestMultiThread(ufJedis,testType,howManyMessages).setTestThreadNumber(x)).start();
        }
        int completedThreads = 0;
        while(completedThreads < howManyThreads){
            try {
                Thread.sleep(1000);
                if(ufJedis.exists("TestMultiThread:"+testType+"Threads:Complete")){
                    completedThreads = Integer.parseInt(ufJedis.get("TestMultiThread:"+testType+"Threads:Complete"));
                    if(completedThreads%10==0){
                        System.out.println(completedThreads+" threads have completed their work...");
                    }
                }
            }catch(Throwable t){
                System.out.println("checking for all threads being complete: "+t.getMessage());
            }
        }
        System.out.println("\n\n*****************\n" +
                testType+":Test took :"+((System.currentTimeMillis()-startTime)/1000)+" seconds" +
                "\n"+testType+":TEST IS COMPLETE... ");
    }

    @Override
    public void run() {
        for(int x=0;x<howManyMessages;x++){
            String connectionName = ""+connectionInstance;
            try{
                connectionInstance.publish("ps:Messages",connectionName+":testThread# "+this.testThreadNumber+" message #"+x);
                connectionInstance.set("tmt:string", ""+x);
            }catch(redis.clients.jedis.exceptions.JedisConnectionException jce){
                System.out.println("THREAD "+this.testThreadNumber+" CAUGHT: JedisConnectionException ");
                x--; // keep trying to do the next thing
            }
            try{
                Thread.sleep(20);
            }catch(Throwable t){
                //do nothing
            }
            // use the following to throw JedisDataException:
            //connectionInstance.set("tmt:string", "y");
            //connectionInstance.incr("tmt:string");
        }
        connectionInstance.incr("TestMultiThread:"+this.testType+"Threads:Complete");
    }
}
