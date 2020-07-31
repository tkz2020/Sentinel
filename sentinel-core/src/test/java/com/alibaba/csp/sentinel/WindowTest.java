package com.alibaba.csp.sentinel;

/**
 * @author tukangzheng
 * @create 2020-07-24 下午8:53
 **/
public class WindowTest {

	private static void calculate(int windowLength,int arrayLength){
		long time = System.currentTimeMillis();
		long timeId = time/windowLength;
		long currentWindowStart = time - time % windowLength;
		int idx = (int)(timeId % arrayLength);
		System.out.println("time="+time+",currentWindowStart="+currentWindowStart+",timeId="+timeId+",idx="+idx);
	}


	public static void main(String[] args) throws InterruptedException {
		//时间窗口的长度
		int windowLength = 500;
		int arrayLength = 2;
		calculate(windowLength,arrayLength);

		Thread.sleep(100);
		calculate(windowLength,arrayLength);

		Thread.sleep(200);
		calculate(windowLength,arrayLength);

		Thread.sleep(200);
		calculate(windowLength,arrayLength);

		Thread.sleep(500);
		calculate(windowLength,arrayLength);

		Thread.sleep(500);
		calculate(windowLength,arrayLength);

		Thread.sleep(500);
		calculate(windowLength,arrayLength);

		Thread.sleep(500);
		calculate(windowLength,arrayLength);

		Thread.sleep(500);
		calculate(windowLength,arrayLength);
	}

}
