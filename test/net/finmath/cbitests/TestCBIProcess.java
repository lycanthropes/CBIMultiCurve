package net.finmath.cbitests;

import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import net.finmath.exception.CalculationException;
import net.finmath.fouriermethod.calibration.models.CBIDrivenMultiCurveModel;
import net.finmath.fouriermethod.products.*;
import net.finmath.fouriermethod.quantization.*;
import net.finmath.marketdata.calibration.CalibratedCurves;
import net.finmath.marketdata.calibration.CalibratedCurves.CalibrationSpec;
import net.finmath.marketdata.model.*;
import net.finmath.marketdata.model.curves.*;
import net.finmath.montecarlo.models.MonteCarloCBIDrivenMultiCurveModel;
import net.finmath.montecarlo.process.MonteCarloFlowOfTemperedCBIProcess;
import net.finmath.montecarlo.products.MonteCarloMultiCurveCapletPricer;
import net.finmath.optimizer.SolverException;
import net.finmath.stochastic.FlowOfTemperedAlphaStableCBIprocess;
import net.finmath.stochastic.MultiCurveTenor;
import net.finmath.marketdata.model.curves.Curve.*;
import net.finmath.time.*;
import net.finmath.time.businessdaycalendar.*;


public class TestCBIProcess {

	public static void main(String[] args) throws SolverException, CloneNotSupportedException, CalculationException {	
		
		/*
		 * Calibration of a single curve - OIS curve - self disocunted curve, from a set of calibration products.
		 */
		LocalDate referenceDate = LocalDate.of(2018,9,24);

		/*
		 * Define the calibration spec generators for our calibration products
		 */
		Function<String,String> frequencyForTenor = (tenor) -> {
			switch(tenor) {
			case "3M":
				return "quarterly";
			case "6M":
				return "semiannual";
			}
			throw new IllegalArgumentException("Unkown tenor " + tenor);
		};

		BiFunction<String, Double, CalibrationSpec> deposit = (maturity, rate) -> {
			ScheduleInterface scheduleInterfaceRec = ScheduleGenerator.createScheduleFromConventions(referenceDate, 2, "0D", maturity, "tenor", "act/360", "first", "following", new BusinessdayCalendarExcludingTARGETHolidays(), 0, 0);
			ScheduleInterface scheduleInterfacePay = null;
			double calibrationTime = scheduleInterfaceRec.getPayment(scheduleInterfaceRec.getNumberOfPeriods()-1);
			CalibrationSpec calibrationSpec = new CalibratedCurves.CalibrationSpec("EUR-OIS-" + maturity, "Deposit", scheduleInterfaceRec, "", rate, "discount-EUR-OIS", scheduleInterfacePay, null, 0.0, null, "discount-EUR-OIS", calibrationTime);
			return calibrationSpec;
		};

		BiFunction<String, Double, CalibrationSpec> swapSingleCurve = (maturity, rate) -> {
			ScheduleInterface scheduleInterfaceRec = ScheduleGenerator.createScheduleFromConventions(referenceDate, 2, "0D", maturity, "annual", "act/360", "first", "modified_following", new BusinessdayCalendarExcludingTARGETHolidays(), 0, 1);
			ScheduleInterface scheduleInterfacePay = ScheduleGenerator.createScheduleFromConventions(referenceDate, 2, "0D", maturity, "annual", "act/360", "first", "modified_following", new BusinessdayCalendarExcludingTARGETHolidays(), 0, 1);
			double calibrationTime = scheduleInterfaceRec.getPayment(scheduleInterfaceRec.getNumberOfPeriods() - 1);
			CalibrationSpec calibrationSpec = new CalibratedCurves.CalibrationSpec("EUR-OIS-" + maturity, "Swap", scheduleInterfaceRec, "forward-EUR-OIS", 0.0, "discount-EUR-OIS", scheduleInterfacePay, "", rate, "discount-EUR-OIS", "discount-EUR-OIS", calibrationTime);
			return calibrationSpec;
		};

		Function<String,BiFunction<String, Double, CalibrationSpec>> fra = (tenor) -> {
			return (fixing, rate) -> {
				ScheduleInterface scheduleInterfaceRec = ScheduleGenerator.createScheduleFromConventions(referenceDate, 2, fixing, tenor, "tenor", "act/360", "first", "modified_following", new BusinessdayCalendarExcludingTARGETHolidays(), 0, 0);
				double calibrationTime = scheduleInterfaceRec.getFixing(scheduleInterfaceRec.getNumberOfPeriods() - 1);
				String curveName = "forward-EUR-" + tenor;
				CalibrationSpec calibrationSpec = new CalibratedCurves.CalibrationSpec("EUR-" + tenor + "-" + fixing, "FRA", scheduleInterfaceRec, curveName, rate, "discount-EUR-OIS", null, null, 0.0, null, curveName, calibrationTime);
				return calibrationSpec;
			};
		};

		Function<String,BiFunction<String, Double, CalibrationSpec>> swap = (tenor) -> {
			return (maturity, rate) -> {
				String frequencyRec = frequencyForTenor.apply(tenor);

				ScheduleInterface scheduleInterfaceRec = ScheduleGenerator.createScheduleFromConventions(referenceDate, 2, "0D", maturity, frequencyRec, "act/360", "first", "following", new BusinessdayCalendarExcludingTARGETHolidays(), 0, 0);
				ScheduleInterface scheduleInterfacePay = ScheduleGenerator.createScheduleFromConventions(referenceDate, 2, "0D", maturity, "annual", "E30/360", "first", "following", new BusinessdayCalendarExcludingTARGETHolidays(), 0, 0);
				double calibrationTime = scheduleInterfaceRec.getFixing(scheduleInterfaceRec.getNumberOfPeriods() - 1);
				String curveName = "forward-EUR-" + tenor;
				CalibrationSpec calibrationSpec = new CalibratedCurves.CalibrationSpec("EUR-" + tenor + maturity, "Swap", scheduleInterfaceRec, curveName, 0.0, "discount-EUR-OIS", scheduleInterfacePay, "", rate, "discount-EUR-OIS", curveName, calibrationTime);
				return calibrationSpec;
			};
		};

		BiFunction<String,String,BiFunction<String, Double, CalibrationSpec>> swapBasis = (tenorRec,tenorPay) -> {
			return (maturity, rate) -> {
				String curveNameRec = "forward-EUR-" + tenorRec;
				String curveNamePay = "forward-EUR-" + tenorPay;

				String frequencyRec = frequencyForTenor.apply(tenorRec);
				String frequencyPay = frequencyForTenor.apply(tenorPay);

				ScheduleInterface scheduleInterfaceRec = ScheduleGenerator.createScheduleFromConventions(referenceDate, 2, "0D", maturity, frequencyRec, "act/360", "first", "following", new BusinessdayCalendarExcludingTARGETHolidays(), 0, 0);
				ScheduleInterface scheduleInterfacePay = ScheduleGenerator.createScheduleFromConventions(referenceDate, 2, "0D", maturity, frequencyPay, "act/360", "first", "following", new BusinessdayCalendarExcludingTARGETHolidays(), 0, 0);
				double calibrationTime = scheduleInterfaceRec.getFixing(scheduleInterfaceRec.getNumberOfPeriods() - 1);

				CalibrationSpec calibrationSpec = new CalibratedCurves.CalibrationSpec("EUR-" + tenorRec + "-" + tenorPay + maturity, "Swap", scheduleInterfaceRec, curveNameRec, 0.0, "discount-EUR-OIS", scheduleInterfacePay, curveNamePay, rate, "discount-EUR-OIS", curveNameRec, calibrationTime);
				return calibrationSpec;
			};
		};

		/*
		 * Generate empty curve template (for cloning during calibration)
		 */
		double[] times = { 0.0 };
		double[] discountFactors = { 1.0 };
		boolean[] isParameter = { false };

		DiscountCurve discountCurveOIS = DiscountCurve.createDiscountCurveFromDiscountFactors("discount-EUR-OIS", referenceDate, times, discountFactors, isParameter, InterpolationMethod.LINEAR, ExtrapolationMethod.CONSTANT, InterpolationEntity.LOG_OF_VALUE);
		ForwardCurveInterface forwardCurveOIS = new ForwardCurveFromDiscountCurve("forward-EUR-OIS", "discount-EUR-OIS", referenceDate, "3M");
		ForwardCurveInterface forwardCurve3M = new ForwardCurve("forward-EUR-3M", referenceDate, "3M", new BusinessdayCalendarExcludingTARGETHolidays(), BusinessdayCalendarInterface.DateRollConvention.FOLLOWING, Curve.InterpolationMethod.LINEAR, Curve.ExtrapolationMethod.CONSTANT, Curve.InterpolationEntity.VALUE,ForwardCurve.InterpolationEntityForward.FORWARD, "discount-EUR-OIS");
		ForwardCurveInterface forwardCurve6M = new ForwardCurve("forward-EUR-6M", referenceDate, "6M", new BusinessdayCalendarExcludingTARGETHolidays(), BusinessdayCalendarInterface.DateRollConvention.FOLLOWING, Curve.InterpolationMethod.LINEAR, Curve.ExtrapolationMethod.CONSTANT, Curve.InterpolationEntity.VALUE,ForwardCurve.InterpolationEntityForward.FORWARD, "discount-EUR-OIS");

		AnalyticModel forwardCurveModel = new AnalyticModel(new CurveInterface[] { discountCurveOIS, forwardCurveOIS, forwardCurve3M, forwardCurve6M });

		List<CalibrationSpec> calibrationSpecs = new LinkedList<>();

		/*
		 * Calibration products for OIS curve: Deposits
		 */
		calibrationSpecs.add(deposit.apply("1D", 0.202 / 100.0));
		calibrationSpecs.add(deposit.apply("1W", 0.195 / 100.0));
		calibrationSpecs.add(deposit.apply("2W", 0.193 / 100.0));
		calibrationSpecs.add(deposit.apply("3W", 0.193 / 100.0));
		calibrationSpecs.add(deposit.apply("1M", 0.191 / 100.0));
		calibrationSpecs.add(deposit.apply("2M", 0.185 / 100.0));
		calibrationSpecs.add(deposit.apply("3M", 0.180 / 100.0));
		calibrationSpecs.add(deposit.apply("4M", 0.170 / 100.0));
		calibrationSpecs.add(deposit.apply("5M", 0.162 / 100.0));
		calibrationSpecs.add(deposit.apply("6M", 0.156 / 100.0));
		calibrationSpecs.add(deposit.apply("7M", 0.150 / 100.0));
		calibrationSpecs.add(deposit.apply("8M", 0.145 / 100.0));
		calibrationSpecs.add(deposit.apply("9M", 0.141 / 100.0));
		calibrationSpecs.add(deposit.apply("10M", 0.136 / 100.0));
		calibrationSpecs.add(deposit.apply("11M", 0.133 / 100.0));
		calibrationSpecs.add(deposit.apply("12M", 0.129 / 100.0));

		/*
		 * Calibration products for OIS curve: Swaps
		 */
		calibrationSpecs.add(swapSingleCurve.apply("15M", 0.118 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("18M", 0.108 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("21M", 0.101 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("2Y", 0.101 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("3Y", 0.194 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("4Y", 0.346 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("5Y", 0.534 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("6Y", 0.723 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("7Y", 0.895 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("8Y", 1.054 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("9Y", 1.189 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("10Y", 1.310 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("11Y", 1.423 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("12Y", 1.520 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("15Y", 1.723 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("20Y", 1.826 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("25Y", 1.877 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("30Y", 1.910 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("40Y", 2.025 / 100.0));
		calibrationSpecs.add(swapSingleCurve.apply("50Y", 2.101 / 100.0));

		/*
		 * Calibration products for 3M curve: FRAs
		 */
		calibrationSpecs.add(fra.apply("3M").apply("0D", 0.322 / 100.0));
		calibrationSpecs.add(fra.apply("3M").apply("1M", 0.329 / 100.0));
		calibrationSpecs.add(fra.apply("3M").apply("2M", 0.328 / 100.0));
		calibrationSpecs.add(fra.apply("3M").apply("3M", 0.326 / 100.0));
		calibrationSpecs.add(fra.apply("3M").apply("6M", 0.323 / 100.0));
		calibrationSpecs.add(fra.apply("3M").apply("9M", 0.316 / 100.0));
		calibrationSpecs.add(fra.apply("3M").apply("12M", 0.360 / 100.0));
		calibrationSpecs.add(fra.apply("3M").apply("15M", 0.390 / 100.0));

		/*
		 * Calibration products for 3M curve: swaps
		 */
		calibrationSpecs.add(swap.apply("3M").apply("2Y", 0.380 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("3Y", 0.485 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("4Y", 0.628 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("5Y", 0.812 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("6Y", 0.998 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("7Y", 1.168 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("8Y", 1.316 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("9Y", 1.442 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("10Y", 1.557 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("12Y", 1.752 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("15Y", 1.942 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("20Y", 2.029 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("25Y", 2.045 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("30Y", 2.097 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("40Y", 2.208 / 100.0));
		calibrationSpecs.add(swap.apply("3M").apply("50Y", 2.286 / 100.0));

		/*
		 * Calibration products for 6M curve: FRAs
		 */

		calibrationSpecs.add(fra.apply("6M").apply("0D", 0.590 / 100.0));
		calibrationSpecs.add(fra.apply("6M").apply("1M", 0.597 / 100.0));
		calibrationSpecs.add(fra.apply("6M").apply("2M", 0.596 / 100.0));
		calibrationSpecs.add(fra.apply("6M").apply("3M", 0.594 / 100.0));
		calibrationSpecs.add(fra.apply("6M").apply("6M", 0.591 / 100.0));
		calibrationSpecs.add(fra.apply("6M").apply("9M", 0.584 / 100.0));
		calibrationSpecs.add(fra.apply("6M").apply("12M", 0.584 / 100.0));

		/*
		 * Calibration products for 6M curve: tenor basis swaps
		 * Note: the fixed bases is added to the second argument tenor (here 3M).
		 */
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("2Y", 0.255 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("3Y", 0.245 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("4Y", 0.227 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("5Y", 0.210 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("6Y", 0.199 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("7Y", 0.189 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("8Y", 0.177 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("9Y", 0.170 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("10Y", 0.164 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("12Y", 0.156 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("15Y", 0.135 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("20Y", 0.125 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("25Y", 0.117 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("30Y", 0.107 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("40Y", 0.095 / 100.0));
		calibrationSpecs.add(swapBasis.apply("6M","3M").apply("50Y", 0.088 / 100.0));

		/*
		 * Calibrate
		 */
		CalibratedCurves calibratedCurves = new CalibratedCurves(calibrationSpecs.toArray(new CalibrationSpec[calibrationSpecs.size()]), forwardCurveModel, 1E-15);

		/*
		 * Get the calibrated model
		 */
		AnalyticModelInterface calibratedModel = calibratedCurves.getModel();
		
		net.finmath.interpolation.RationalFunctionInterpolation.InterpolationMethod intMethod = net.finmath.interpolation.RationalFunctionInterpolation.InterpolationMethod.HARMONIC_SPLINE;
		net.finmath.interpolation.RationalFunctionInterpolation.ExtrapolationMethod extMethod = net.finmath.interpolation.RationalFunctionInterpolation.ExtrapolationMethod.CONSTANT;
		
		double[] initialValues = {0.02, 0.04};
		double[] immigrationRates = {0.02, 0.04};
		double b = 0.3;
		double sigma = 0.5;
		double eta = 0.5;
		double zeta = 0.2;
		double alpha = 1.8;
		double[] lambda = {0.2, 0.4};
	
		MultiCurveTenor threeMonth = new MultiCurveTenor(0.25, "3M");
		MultiCurveTenor sixMonth = new MultiCurveTenor(0.5, "6M");
		MultiCurveTenor[] tenors = {threeMonth,sixMonth};
		
		double timeHorizon = 10.0;
		
		int numberOfTimeSteps = 150;
		
		CBIDrivenMultiCurveModel model = new CBIDrivenMultiCurveModel(timeHorizon, numberOfTimeSteps, calibratedModel, tenors, initialValues, immigrationRates, b, sigma, eta, zeta, alpha, lambda);
		
		TimeDiscretizationInterface discr = new TimeDiscretization(0.0, numberOfTimeSteps, timeHorizon/numberOfTimeSteps);
		
		FlowOfTemperedAlphaStableCBIprocess cbiProcess = new FlowOfTemperedAlphaStableCBIprocess(timeHorizon, numberOfTimeSteps, initialValues, immigrationRates, b, sigma, eta, zeta, alpha, lambda);
		
		MonteCarloFlowOfTemperedCBIProcess myProcess = new  MonteCarloFlowOfTemperedCBIProcess(10, 1000, discr,  cbiProcess);
			
		MonteCarloCBIDrivenMultiCurveModel mcModel = new MonteCarloCBIDrivenMultiCurveModel(calibratedModel, myProcess, tenors);
		
		int N = 16;
		
		int points = 1024*4;
		
		double[] strikes = {-0.005};
		
		MultiCurveTenor tenor = new MultiCurveTenor(0.5, "forward-EUR-6M");
	
		System.out.println("Strike: " + strikes[0] + ", Tenor: " + tenor.getTenorName() + ", Level of Quantization: " + N);
		
		System.out.println("FFT price             " + "Monte-Carlo price     " + "Quantization price    " + "Maturity   ");
		
		for(int i = 0; i < 30; i++) {
			
			double maturity = 0.1 + i*0.1;
			
			CapletByCarrMadan smile = new CapletByCarrMadan(tenor.getTenorName(), maturity, strikes, points, 0.01,  intMethod,  extMethod);
			
			Map<Double, Double> prices = smile.getValue(model);

			MonteCarloMultiCurveCapletPricer myCapletMC = new MonteCarloMultiCurveCapletPricer(strikes[0], maturity, tenor.getTenorName());
			
			QuantizableCBIDrivenMultiCurveModel quantizedModel = new QuantizableCBIDrivenMultiCurveModel(maturity, N, model, tenor);
			
			QuantizationMultiCurveCapletPricer myCapletQunt = new QuantizationMultiCurveCapletPricer(strikes[0], maturity, tenor);
					
			System.out.println(prices.get(strikes[0]) + "  " + myCapletMC.getPrice(mcModel) + "  " + myCapletQunt.getValue(quantizedModel) + "  " + maturity);
			
		}	

	}

}