package com.deeplearning.layer

import breeze.linalg.{DenseMatrix, DenseVector}
import com.deeplearning.Network.generateRandomBiasFloat
import com.deeplearning.bspline.{BSpline, BSplineFigure, ControlPointUpdater, KnotPoints}
import com.deeplearning.bspline.KANControlPoints.{expand, initializeControlPoints2D}
import com.deeplearning.{ActivationManager, ComputeActivation, CostManager, LayerManager, Network}
import com.deeplearning.samples.{CifarData, MnistData, TrainingDataSet}

import java.time.Instant

class DenseInputLayer extends InputLayer {
  var parameterSended = false
  var nablas_w_tmp = Array.empty[Float]
  var knots : DenseVector[Double] = _
  var k = 3 //degree of the spline
  var c = k + 2
  var controlPoints :Array[Array[DenseMatrix[Double]]]= _
  var ts = Array[Array[Float]]()
  var tsTest : Array[Array[Float]] = null
  var ws = Array[Float]()
  var wb = Array[Float]()
  var inputSize = 0
  var gridExtension = 0

  private var dataSet: TrainingDataSet = if (Network.trainingSample == "Mnist") {
    println("Loading Dataset MINST")
    dataSet = new MnistData()
    dataSet
  } else {
    dataSet = new CifarData()
    dataSet
  }
  def computeInputWeights(epoch:Int, correlationId: String, yLabel:Int, startIndex:Int, endIndex:Int, index:Int, layer:Int, internalSubLayer:Int,params: scala.collection.mutable.HashMap[String,String]): Array[Array[Float]] = {
    if (lastEpoch != epoch) {
      counterTraining = 0
      counterBackPropagation = 0
      counterFeedForward = 0
      lastEpoch = epoch
      gridExtension+=1
      knots = KnotPoints.initializeKnotPoints2D(c+gridExtension,k)
      ts.zipWithIndex.map {
        case (element, i) =>
          element.zipWithIndex.map {
            case (subelement, j) => {
              this.controlPoints(i)(j) = expand(this.controlPoints(i)(j),subelement)
            }
          }
      }

      if (internalSubLayer==0)
        println("Input " +  controlPoints(0)(0).toArray(5) + " "  + controlPoints(0)(0).toArray(6)+ " "  + controlPoints(0)(0).toArray(7)+ " "  + controlPoints(0)(0).toArray(8)+ " "  + controlPoints(0)(0).toArray(9))
        BSplineFigure.draw(controlPoints(0)(0),knots,k,epoch,layer,internalSubLayer)
    }
    epochCounter = epoch
    counterTraining += 1
    val startedAt = Instant.now
    val nextLayer = layer+1

    val receiverUCs = Network.getHiddenLayers(1, "hidden")

    if (!wInitialized) {
      val arraySize = Network.InputLayerDim
      nabla_w = Array.ofDim(arraySize)
      dataSet.loadTrainDataset(Network.InputLoadMode)

      wInitialized = true
      wb =  generateRandomBiasFloat(receiverUCs)
      ws = Array.fill[Float](receiverUCs)(1.0f)
      inputSize = endIndex-startIndex

      // randomly initializing the control points
      controlPoints = Array.fill(endIndex-startIndex, receiverUCs) {
        initializeControlPoints2D(c)
      }// Convert each Seq to Array

      knots = KnotPoints.initializeKnotPoints2D(c,k)

      if (internalSubLayer==0)
        BSplineFigure.draw(controlPoints(0)(0),knots,k,epoch,layer,internalSubLayer)
    }

    if (!minibatch.contains(correlationId)) {
      minibatch += (correlationId -> 0)
      val input = dataSet.getTrainingInput(index)
      val x = input.slice(startIndex + 2, endIndex + 2) //normalisation
      val v = CostManager.divide(x, 255)
      this.X += (correlationId -> v)
      this.ts = null
    }

    ts = controlPoints.indices.map (
      i => controlPoints(i).indices.map {
        val x = this.X(correlationId)(i)
        j =>
          val spline = wb(j)* BSpline.compute(controlPoints(i)(j),k,x,knots).toFloat
          val act = ws(j)*ActivationManager.ComputeZ(Network.getActivationLayersType(layer), x)
          act +  spline
      }.toArray
    ).toArray

    //sum the same index
    val ts2 = ts.transpose.map(_.sum)
    //val data = DenseVector(ts2)
    //val normalizedData = normalize(data).toArray

    if (!parameterSended) {
    //  parameters("min") = weights.min.toString
    //  parameters("max") = weights.max.toString
      parameterSended = true
    }

    for (i: Int <- 0 until Network.getHiddenLayersDim(nextLayer, "hidden")) {
      val actorHiddenLayer = Network.LayersHiddenRef("hiddenLayer_" + nextLayer + "_" + i)
      actorHiddenLayer ! ComputeActivation.ComputeZ(epoch, correlationId, yLabel, Network.MiniBatchRange, ts2, i, nextLayer, Network.InputLayerDim, params, Array.empty[Float])
    }
    null
  }

  def BackPropagate(correlationId: String, delta: Array[Float], learningRate: Float, regularisation: Float, nInputs: Float, internalSubLayer: Int, fromInternalSubLayer: Int, params: scala.collection.mutable.HashMap[String,String]): Boolean = {
    counterBackPropagation += 1
    minibatch(correlationId) += 1
    //params("eventBP") =  (params("eventBP").toInt + 1).toString

    if (!backPropagateReceived.contains(correlationId)) {
      val fromArraySize = Network.getHiddenLayersDim(1, "hidden")
      backPropagateReceived += (correlationId -> true)
      //nablas_w(correlationId) = Array.ofDim(fromArraySize)
    }
    var startedAt = Instant.now
    val x = this.X(correlationId)
    if (nablas_w_tmp.isEmpty) {
      val tt = controlPoints.indices.map (
        i => controlPoints(i).indices.map {
          val siluPrime = ActivationManager.ComputePrime(Network.getActivationLayersType(1), x(i))
          j =>
            val index = i*j+j
            val splinePrime = BSpline.bsplineDerivative(ts(i)(j), controlPoints(i)(j).toDenseVector,knots,k).toFloat
            val finald = siluPrime + splinePrime
            finald
        }.toArray
      ).toArray
      nablas_w_tmp = CostManager.matMul(tt.transpose, delta)
    }
    else {
      val tt = controlPoints.indices.map (
        i => controlPoints(i).indices.map {
          val siluPrime = ActivationManager.ComputePrime(Network.getActivationLayersType(1), x(i))
          j =>
            val index = i*j+j
            val splinePrime = BSpline.bsplineDerivative(ts(i)(j), controlPoints(i)(j).toDenseVector,knots,k).toFloat
            val finald = siluPrime + splinePrime
            finald
        }.toArray
      ).toArray
      nablas_w_tmp = CostManager.sum2(nablas_w_tmp,CostManager.matMul(tt.transpose, delta) )
    }
    //////////////////////////nablas_w(correlationId)(fromInternalSubLayer) = dot
    // how many calls would we received
    val callerSize = Network.getHiddenLayersDim(1, "hidden")

    // check if we reach the last mini-bacth
    //context.log.info("Receiving from bakpropagation")
    if ((Network.MiniBatch * callerSize) == minibatch.values.sum) {
      val a = Network.rangeInitStart
      val b = Network.rangeInitEnd
      parameterSended = false
      params("min") = "0"
      params("max") = "0"
      val size = LayerManager.GetInputLayerStep()

      var tmp1 = CostManager.matMulScalar(1 - learningRate * (regularisation / nInputs), this.ts.flatten)
      val tmp2 = CostManager.matMulScalar(learningRate / Network.MiniBatch,nablas_w_tmp)
      val tmp3 = tmp1.grouped(inputSize).toArray
      val tmp4 = CostManager.minus2(tmp3, tmp2)
      //tmp1 = CostManager.matMul(tmp3,delta)
      //tmp1 = CostManager.scaleToRange(tmp1)
      //val tmp6 = normalize(DenseVector(tmp4)).toArray

      //println("Before : Input " +  controlPoints(0)(0).toArray(4) + " "  + controlPoints(0)(0).toArray(5)+ " "  + controlPoints(0)(0).toArray(6)+ " "  + controlPoints(0)(0).toArray(7))
      ts.zipWithIndex.map {
        case (element, i) =>
          element.zipWithIndex.map {
            case (subelement, j) => {
              //if (i==0 && j==0) println("Before : " + controlPoints(i)(j))
              val index2 = element.length *i + j
              this.controlPoints(i)(j) = ControlPointUpdater.updateControlPoints(x(i), knots, controlPoints(i)(j), k, tmp4(index2)).toDenseMatrix
              //if (i==0 && j==0)  println("After : " + controlPoints(i)(j))
            }
          }
      }

    //  this.ts -= (correlationId)
      backPropagateReceived.clear()
      minibatch.clear()
      weighted.clear()
      nablas_w.clear()
      counterBackPropagation=0
      nablas_w_tmp = Array.empty[Float]

      this.X.clear()
      true
    }
    else
      false
  }

  def FeedForwardTest(correlationId: String, startIndex: Int, endIndex: Int, index: Int, internalSubLayer: Int, layer: Int):  Array[Array[Float]] = {
    if (!wTest) {
      wTest = true
      //Temporary
      // read shard data from data lake
      dataSet.loadTestDataset(Network.InputLoadMode)
    }

    val nextLayer = layer + 1
    if (!minibatch.contains(correlationId)) {
      minibatch += (correlationId -> 0)
      //for (i <- index until (index+1)) {
      val input = dataSet.getTestInput(index)
      val x = input.slice(startIndex + 2, endIndex + 2) //normalisation
      val v = CostManager.divide(x, 255)
      this.XTest += (correlationId -> v)
      wTest = true
    }

    tsTest = controlPoints.indices.map (
      i => controlPoints(i).indices.map {
        val x = this.XTest(correlationId)(i)
        j =>
          ActivationManager.ComputeZ(Network.getActivationLayersType(layer), x) + BSpline.compute(controlPoints(i)(j),k,x,knots).toFloat
      }.toArray
    ).toArray

    //sum the same index
    val ts2 = tsTest.transpose.map(_.sum)
    for (i: Int <- 0 until Network.getHiddenLayersDim(nextLayer, "hidden")) {
      val actorHiddenLayer = Network.LayersHiddenRef("hiddenLayer_" + nextLayer + "_" + i)
      actorHiddenLayer ! ComputeActivation.FeedForwardTest(correlationId, ts2, i, nextLayer, Network.InputLayerDim)
    }

    weighted -= (correlationId)
    minibatch -= (correlationId)
    XTest -= (correlationId)
    null
  }
}
