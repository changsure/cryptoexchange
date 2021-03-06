package com.itranswarp.crypto.match;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.itranswarp.crypto.order.OrderMessage;
import com.itranswarp.crypto.queue.MessageQueue;
import com.itranswarp.crypto.store.AbstractRunnableService;

/**
 * Match engine is single-threaded service to process order messages and
 * produces match results, depth and tick messages.
 * 
 * Match engine is stateless: same input sequence always produces same match
 * results sequence.
 * 
 * @author liaoxuefeng
 */
@Component
public class MatchService extends AbstractRunnableService {

	// get order from queue:
	final MessageQueue<OrderMessage> orderMessageQueue;

	// send tick to queue:
	final MessageQueue<Tick> tickMessageQueue;

	// send match result to queue:
	final MessageQueue<MatchResult> matchResultMessageQueue;

	// holds order books:
	final OrderBook buyBook;
	final OrderBook sellBook;

	// track market price:
	BigDecimal marketPrice = BigDecimal.ZERO;

	// matcher internal status:
	HashStatus hashStatus = new HashStatus();

	public MatchService(@Autowired @Qualifier("orderMessageQueue") MessageQueue<OrderMessage> orderMessageQueue,
			@Autowired @Qualifier("tickMessageQueue") MessageQueue<Tick> tickMessageQueue,
			@Autowired @Qualifier("matchResultMessageQueue") MessageQueue<MatchResult> matchResultMessageQueue) {
		this.orderMessageQueue = orderMessageQueue;
		this.tickMessageQueue = tickMessageQueue;
		this.matchResultMessageQueue = matchResultMessageQueue;
		this.buyBook = new OrderBook(OrderBook.OrderBookType.BUY);
		this.sellBook = new OrderBook(OrderBook.OrderBookType.SELL);
	}

	@Override
	protected void process() throws InterruptedException {
		while (true) {
			OrderMessage order = orderMessageQueue.getMessage();
			processOrder(order);
		}
	}

	@Override
	protected void clean() throws InterruptedException {
		while (true) {
			OrderMessage order = orderMessageQueue.getMessage(10);
			if (order != null) {
				processOrder(order);
			} else {
				break;
			}
		}
	}

	/**
	 * Process an order.
	 * 
	 * @param order
	 *            Order object.
	 */
	void processOrder(OrderMessage order) throws InterruptedException {
		switch (order.type) {
		case BUY_LIMIT:
			processBuyLimit(order);
			break;
		case SELL_LIMIT:
			processSellLimit(order);
			break;
		case BUY_MARKET:
			throw new RuntimeException("Unsupported type.");
		case SELL_MARKET:
			throw new RuntimeException("Unsupported type.");
		case BUY_CANCEL:
			throw new RuntimeException("Unsupported type.");
		case SELL_CANCEL:
			throw new RuntimeException("Unsupported type.");
		default:
			throw new RuntimeException("Unsupported type.");
		}
	}

	void processBuyLimit(OrderMessage buyTaker) throws InterruptedException {
		MatchResult matchResult = new MatchResult();
		for (;;) {
			OrderMessage sellMaker = this.sellBook.getFirst();
			if (sellMaker == null) {
				// empty order book:
				break;
			}
			if (buyTaker.price.compareTo(sellMaker.price) < 0) {
				break;
			}
			// match with sellMaker.price:
			this.marketPrice = sellMaker.price;
			// max amount to exchange:
			BigDecimal amount = buyTaker.amount.min(sellMaker.amount);
			buyTaker.amount = buyTaker.amount.subtract(amount);
			sellMaker.amount = sellMaker.amount.subtract(amount);
			notifyTicker(buyTaker.createdAt, this.marketPrice, amount);
			matchResult.addMatchRecord(new MatchRecord(buyTaker.id, sellMaker.id, this.marketPrice, amount));
			updateHashStatus(buyTaker, sellMaker, this.marketPrice, amount);
			if (sellMaker.amount.compareTo(BigDecimal.ZERO) == 0) {
				this.sellBook.remove(sellMaker);
			}
			if (buyTaker.amount.compareTo(BigDecimal.ZERO) == 0) {
				buyTaker = null;
				break;
			}
		}
		if (buyTaker != null) {
			this.buyBook.add(buyTaker);
		}
		if (!matchResult.isEmpty()) {
			notifyMatchResult(matchResult);
		}
	}

	void processSellLimit(OrderMessage sellTaker) throws InterruptedException {
		MatchResult matchResult = new MatchResult();
		for (;;) {
			OrderMessage buyMaker = this.buyBook.getFirst();
			if (buyMaker == null) {
				// empty order book:
				break;
			}
			if (sellTaker.price.compareTo(buyMaker.price) > 0) {
				break;
			}
			// match with buyMaker.price:
			this.marketPrice = buyMaker.price;
			// max amount to match:
			BigDecimal amount = sellTaker.amount.min(buyMaker.amount);
			sellTaker.amount = sellTaker.amount.subtract(amount);
			buyMaker.amount = buyMaker.amount.subtract(amount);
			notifyTicker(sellTaker.createdAt, this.marketPrice, amount);
			matchResult.addMatchRecord(new MatchRecord(sellTaker.id, buyMaker.id, this.marketPrice, amount));
			updateHashStatus(sellTaker, buyMaker, this.marketPrice, amount);
			if (buyMaker.amount.compareTo(BigDecimal.ZERO) == 0) {
				this.buyBook.remove(buyMaker);
			}
			if (sellTaker.amount.compareTo(BigDecimal.ZERO) == 0) {
				sellTaker = null;
				break;
			}
		}
		if (sellTaker != null) {
			this.sellBook.add(sellTaker);
		}
		if (!matchResult.isEmpty()) {
			notifyMatchResult(matchResult);
		}
	}

	void notifyTicker(long time, BigDecimal price, BigDecimal amount) throws InterruptedException {
		Tick tick = new Tick(time, price, amount);
		this.tickMessageQueue.sendMessage(tick);
	}

	void notifyMatchResult(MatchResult matchResult) throws InterruptedException {
		this.matchResultMessageQueue.sendMessage(matchResult);
	}

	private final ByteBuffer hashBuffer = ByteBuffer.allocate(128);

	private void updateHashStatus(OrderMessage taker, OrderMessage maker, BigDecimal price, BigDecimal amount) {
		hashBuffer.clear();
		hashBuffer.putLong(taker.id);
		hashBuffer.putInt(taker.type.value);
		hashBuffer.putLong(maker.id);
		hashBuffer.putInt(maker.type.value);
		hashBuffer.put(price.toString().getBytes(StandardCharsets.UTF_8));
		hashBuffer.put(amount.toString().getBytes(StandardCharsets.UTF_8));
		this.hashStatus.updateStatus(hashBuffer);
	}

	public byte[] getHashStatus() {
		return this.hashStatus.getStatus();
	}

	public void dump() {
		System.out.println(String.format("S: %5d more", this.sellBook.size()));
		this.sellBook.dump(true);
		System.out.println(String.format("P: $%.4f ----------------", this.marketPrice));
		this.buyBook.dump(false);
		System.out.println(String.format("B: %5d more", this.buyBook.size()));
		System.out.println(String.format("%032x\n", new BigInteger(1, this.hashStatus.getStatus())));
	}

}
